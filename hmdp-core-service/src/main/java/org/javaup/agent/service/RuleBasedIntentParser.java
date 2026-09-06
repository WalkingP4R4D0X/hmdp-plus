package org.javaup.agent.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.agent.model.AgentModels;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedIntentParser implements IntentParser {
    private static final String STATUS_INVALID_INPUT = "INVALID_INPUT";
    private static final String STATUS_MODEL_FALLBACK = "MODEL_FALLBACK";
    private static final String STATUS_MODEL_EMPTY = "MODEL_EMPTY";
    private static final Pattern MONEY=Pattern.compile("(?:人均|预算|每人)?\\s*(\\d{2,5})\\s*(?:元|块)?(?:以内|以下|内)?");
    private static final Pattern RADIUS=Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米)");
    private static final Pattern SCORE=Pattern.compile("(?:评分|分数)\\s*(?:不低于|至少|大于等于)?\\s*(\\d(?:\\.\\d)?)");
    @Resource
    private DeepSeekClient deepSeekClient;
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
                    .warn("agent_llm_intent_fallback messageLength={} reason={}", message.length(), e.getMessage());
            modelFailed = true;
        }
        if (i == null) i = parseText(message);
        mergeHistory(i, history);
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
    private static void mergeHistory(AgentModels.Intent i, List<AgentModels.Message> history) {
        if (history == null) return;
        for(int n=history.size()-1;n>=0;n--){AgentModels.Intent o=history.get(n).getFilters();if(o==null)continue;if(i.getBudgetMax()==null)i.setBudgetMax(o.getBudgetMax());if(i.getRadiusMeter()==null)i.setRadiusMeter(o.getRadiusMeter());if(StrUtil.isBlank(i.getKeyword()))i.setKeyword(o.getKeyword());if(StrUtil.isBlank(i.getLocation()))i.setLocation(o.getLocation());if(i.getMinScore()==null)i.setMinScore(o.getMinScore());if(i.getOpenAt()==null)i.setOpenAt(o.getOpenAt());if(i.getScene()==null)i.setScene(o.getScene());if(i.getNeedVoucher()==null)i.setNeedVoucher(o.getNeedVoucher());break;}
    }
    public static AgentModels.Intent parseText(String t){AgentModels.Intent i=new AgentModels.Intent();i.setKeyword(keyword(t));i.setRadiusMeter(radius(t));if(i.getRadiusMeter()==null&&t.contains("附近"))i.setRadiusMeter(3000);i.setBudgetMax(money(t));i.setMinScore(score(t));i.setNeedVoucher(t.contains("券")||t.contains("优惠"));i.setScene(scene(t));i.setLocation(location(t));Matcher m=Pattern.compile("(晚上|夜里)?\\s*(\\d{1,2})(?:点|:00)").matcher(t);if(m.find()){int hour=Integer.parseInt(m.group(2));if(m.group(1)!=null&&hour<12)hour+=12;i.setOpenAt(String.format("%02d:00",hour));}return i;}
    private static String keyword(String t){
        // Only emit a known category. Generic wording such as "有什么好吃的"
        // must mean an unconstrained food search, not a literal name fragment.
        for(String x:new String[]{"日料","寿司","刺身","火锅","咖啡","烧烤","甜品","KTV","唱歌","酒吧","轰趴馆","轰趴","亲子游乐","亲子","健身","按摩","足疗","美容SPA","美容","美发","美甲","美睫"}) if(t.contains(x)) return x;
        return null;
    }
    private static Integer money(String t){Matcher m=MONEY.matcher(t);return m.find()?Integer.valueOf(m.group(1)):null;} private static Integer radius(String t){Matcher m=RADIUS.matcher(t);if(!m.find())return null;return (int)(m.group(2).equals("米")?Double.parseDouble(m.group(1)):Double.parseDouble(m.group(1))*1000);} private static Double score(String t){Matcher m=SCORE.matcher(t);return m.find()?Double.valueOf(m.group(1)):null;} private static String location(String t){for(String x:new String[]{"拱墅区","西湖区","上城区","下城区"})if(t.contains(x))return x;return null;} private static String scene(String t){for(String x:new String[]{"约会","聚餐","亲子","拍照","夜宵"})if(t.contains(x))return x;return null;}
}
