package org.javaup.agent.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.agent.model.AgentModels;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedIntentParser implements IntentParser {
    private static final String STATUS_INVALID_INPUT = "INVALID_INPUT";
    private static final String STATUS_MODEL_FALLBACK = "MODEL_FALLBACK";
    private static final String STATUS_MODEL_EMPTY = "MODEL_EMPTY";
    private static final String NUMBER = "([+-]?\\d+(?:\\.\\d+)?)";
    private static final Pattern MONEY = Pattern.compile(
            "(?:人均|预算|每人)\\s*(?:控制在|改成|改为|调整为|不超过|不高于|最多|至多|在|为|是)?\\s*" + NUMBER
                    + "(?![\\d.])(?!\\s*(?:公里|千米|km|米|分钟|小时|点|[:：]|分|个小时))"
                    + "|(?<![\\d.])" + NUMBER + "\\s*(?:元|块)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RADIUS = Pattern.compile(NUMBER + "\\s*(公里|千米|km|米)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCORE = Pattern.compile("(?:评分|分数)\\s*(?:不低于|至少|大于等于|改成|改为)?\\s*" + NUMBER);
    private static final Pattern TIME = Pattern.compile("(?<![\\d.])(晚上|夜里|下午|中午|早上|上午|凌晨)?\\s*(\\d{1,2})(?:点(?:(\\d{1,2})分?|半)?|[:：](\\d{2}))(?!\\d)");
    @Resource
    private DeepSeekClient deepSeekClient;
    @Resource
    private IntentNormalizer intentNormalizer = new IntentNormalizer();
    @Override public AgentModels.Intent parse(String message,List<AgentModels.Message> history){
        if (isGreeting(message)) return greetingIntent();
        if (isLikelyGibberish(message)) {
            AgentModels.Intent invalid = new AgentModels.Intent();
            invalid.setParseStatus(STATUS_INVALID_INPUT);
            return invalid;
        }
        AgentModels.Intent i = null;
        boolean modelFailed = false;
        try {
            i = deepSeekClient == null ? null : deepSeekClient.parseIntent(message, history);
        } catch (RuntimeException e) {
            // A model outage is handled by the deterministic parser below.
            org.slf4j.LoggerFactory.getLogger(RuleBasedIntentParser.class)
                    .warn("agent_llm_intent_fallback exceptionType={}", e.getClass().getSimpleName());
            modelFailed = true;
        }
        if (i == null) {
            modelFailed = true;
            i = parseText(message);
        }
        // Status is owned by code, never by the model or a previous conversation turn.
        i.setParseStatus(null);
        i = intentNormalizer.merge(i, history, message);
        if (modelFailed) i.setParseStatus(STATUS_MODEL_FALLBACK);
        else if (!hasConstraint(i)) i.setParseStatus(STATUS_MODEL_EMPTY);
        return i;
    }
    private static boolean isLikelyGibberish(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return true;
        if (value.matches(".*[\\u4e00-\\u9fff].*")) return false;
        if (value.matches(".*\\d.*")) return false;
        String normalized = value.replaceAll("[^A-Za-z]", "").toLowerCase();
        return !normalized.matches("hello|hi|help|restaurant|shop|food|sushi|hotpot|coffee|ktv");
    }
    private static boolean hasConstraint(AgentModels.Intent i) {
        return StrUtil.isNotBlank(i.getKeyword()) || StrUtil.isNotBlank(i.getLocation()) || i.getRadiusMeter() != null
                || i.getBudgetMax() != null || i.getMinScore() != null || StrUtil.isNotBlank(i.getOpenAt())
                || StrUtil.isNotBlank(i.getScene()) || Boolean.TRUE.equals(i.getNeedVoucher());
    }
    private static boolean isGreeting(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("[!！。,.，?？~～\\s]", "").toLowerCase();
        return normalized.equals("你好") || normalized.equals("您好") || normalized.equals("哈喽")
                || normalized.equals("hello") || normalized.equals("hi") || normalized.equals("嗨") || normalized.equals("在吗");
    }
    private static AgentModels.Intent greetingIntent() {
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setIntent("GREETING");
        return intent;
    }
    public static AgentModels.Intent parseText(String text) {
        String t = text == null ? "" : text;
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setKeyword(keyword(t));
        intent.setRadiusMeter(radius(t));
        intent.setBudgetMax(money(t));
        intent.setMinScore(score(t));
        intent.setNeedVoucher(t.contains("券") || t.contains("优惠") ? Boolean.TRUE : null);
        intent.setScene(scene(t));
        intent.setLocation(location(t));
        Matcher time = TIME.matcher(t);
        if (time.find()) {
            int hour = Integer.parseInt(time.group(2));
            String period = time.group(1);
            if (List.of("晚上", "夜里", "下午", "中午").contains(period == null ? "" : period) && hour < 12) hour += 12;
            if ("凌晨".equals(period) && hour == 12) hour = 0;
            int minute = time.group(3) != null ? Integer.parseInt(time.group(3))
                    : time.group(4) != null ? Integer.parseInt(time.group(4)) : time.group().endsWith("半") ? 30 : 0;
            intent.setOpenAt(String.format(Locale.ROOT, "%02d:%02d", hour, minute));
        }
        return new IntentNormalizer().merge(intent, List.of(), t);
    }
    private static String keyword(String t){
        // Prefer known categories, then conservatively extract a named search target.
        for(String x:new String[]{"日料","寿司","刺身","火锅","咖啡","烧烤","甜品","KTV","唱歌","酒吧","轰趴馆","轰趴","亲子游乐","亲子","健身","按摩","足疗","美容SPA","美容","美发","美甲","美睫"}) if(t.contains(x)) return x;
        Matcher name = Pattern.compile("(?:找|搜(?:索)?|查(?:找)?|推荐)(?:一下|几家|一家|家)?\\s*([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9·&\\-]{1,39})").matcher(t);
        if (!name.find()) return null;
        String candidate = name.group(1).replaceFirst("(?:有没有|有无|附近|人均|预算|距离|评分|晚上|营业|优惠|便宜|适合|的店|的门店|门店|分店).*$", "")
                .replaceFirst("(?:在哪儿|在哪里|在哪|怎么样|好吗|吗|吧|呢)$", "");
        if (candidate.isBlank() || candidate.matches(".*(?:什么|好吃|餐厅|餐馆|饭店|商户|店铺|美食|推荐|一下|几家|一家|区域|时间).*")) return null;
        return candidate;
    }

    private static Integer money(String text) {
        Matcher match = MONEY.matcher(text);
        if (!match.find()) return null;
        return boundedInteger(Double.parseDouble(match.group(1) == null ? match.group(2) : match.group(1)));
    }

    private static Integer radius(String text) {
        Matcher match = RADIUS.matcher(text);
        if (!match.find()) return null;
        double value = Double.parseDouble(match.group(1));
        return boundedInteger("米".equals(match.group(2)) ? value : value * 1000);
    }

    private static Integer boundedInteger(double value) {
        return !Double.isFinite(value) || value < 0 || value > Integer.MAX_VALUE ? null : (int) Math.floor(value);
    }

    private static Double score(String text) {
        Matcher match = SCORE.matcher(text);
        return match.find() ? Double.valueOf(match.group(1)) : null;
    }

    private static String location(String text) {
        for (String value : new String[]{"拱墅区", "西湖区", "上城区", "下城区"}) if (text.contains(value)) return value;
        return null;
    }

    private static String scene(String text) {
        for (String value : new String[]{"约会", "聚餐", "亲子", "拍照", "夜宵"}) if (text.contains(value)) return value;
        return null;
    }
}
