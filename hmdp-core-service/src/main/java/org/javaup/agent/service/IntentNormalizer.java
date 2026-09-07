package org.javaup.agent.service;

import org.javaup.agent.model.AgentModels;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies server-side bounds and defaults to model-produced intent fields. */
@Component
public class IntentNormalizer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> SCENES = Set.of("约会", "聚餐", "亲子", "拍照", "夜宵");
    private static final Set<String> INTENTS = Set.of("SHOP_RECOMMENDATION", "GREETING");
    private static final String BUDGET = "预算|人均|价格";
    private static final String RADIUS = "距离|半径|范围";
    private static final String SCORE = "评分|分数";
    private static final String OPEN_AT = "营业时间|营业时段|营业要求|时间";
    private static final String LOCATION = "区域|地区|位置|地点";
    private static final String VOUCHER = "优惠券|代金券|优惠|券";
    private static final String FIELDS = BUDGET + "|" + RADIUS + "|" + SCORE + "|" + OPEN_AT + "|" + LOCATION + "|" + VOUCHER;

    /** Merge a current-turn delta with the latest complete filter snapshot, including explicit removals. */
    public AgentModels.Intent merge(AgentModels.Intent current, List<AgentModels.Message> history, String message) {
        AgentModels.Intent result = normalize(copy(current));
        String text = message == null ? "" : message;
        // Defaults describe this turn and must override older values when explicitly requested.
        if (result.getRadiusMeter() == null && text.contains("附近")) result.setRadiusMeter(3000);
        if (result.getBudgetMax() == null && text.contains("便宜")) result.setBudgetMax(100);
        if (history != null) {
            for (int n = history.size() - 1; n >= 0; n--) {
                AgentModels.Message item = history.get(n);
                if (item == null || item.getFilters() == null) continue;
                AgentModels.Intent previous = normalize(copy(item.getFilters()));
                if (result.getKeyword() == null) result.setKeyword(previous.getKeyword());
                if (result.getLocation() == null) result.setLocation(previous.getLocation());
                if (result.getRadiusMeter() == null) result.setRadiusMeter(previous.getRadiusMeter());
                if (result.getBudgetMax() == null) result.setBudgetMax(previous.getBudgetMax());
                if (result.getMinScore() == null) result.setMinScore(previous.getMinScore());
                if (result.getOpenAt() == null) result.setOpenAt(previous.getOpenAt());
                if (result.getScene() == null) result.setScene(previous.getScene());
                if (result.getNeedVoucher() == null) result.setNeedVoucher(previous.getNeedVoucher());
                // Nulls in this snapshot may be deliberate removals. Never resurrect older filters.
                break;
            }
        }
        if (clears(text, BUDGET)) result.setBudgetMax(null);
        if (clears(text, RADIUS)) result.setRadiusMeter(null);
        if (clears(text, SCORE)) result.setMinScore(null);
        if (clears(text, OPEN_AT)) result.setOpenAt(null);
        if (clears(text, LOCATION)) result.setLocation(null);
        if (clears(text, VOUCHER)) result.setNeedVoucher(null);
        return normalize(result);
    }

    private static boolean clears(String text, String field) {
        String target = "(?:" + field + ")";
        String suffix = "(?:的)?(?:限制|要求|条件)?";
        String prefix = "(?:取消|清除|去掉|去除|移除|删除|不限|不限制|不限定|不要求|不需要|不用|不要|不看|不考虑)\\s*(?:管|考虑|限制|要求|对)?\\s*";
        String precedingFields = "(?:(?:" + FIELDS + ")" + suffix + "\\s*[、/和及与]\\s*)*";
        return Pattern.compile(prefix + precedingFields + target).matcher(text).find()
                || Pattern.compile(target + suffix + "\\s*(?:都)?(?:不限|不限制|不限定|不要求|不需要|不用|不要|无所谓|都行|取消|去掉|清除)").matcher(text).find();
    }

    private static AgentModels.Intent copy(AgentModels.Intent source) {
        AgentModels.Intent result = new AgentModels.Intent();
        if (source == null) return result;
        result.setIntent(source.getIntent());
        result.setParseStatus(source.getParseStatus());
        result.setKeyword(source.getKeyword());
        result.setLocation(source.getLocation());
        result.setLatitude(source.getLatitude());
        result.setLongitude(source.getLongitude());
        result.setRadiusMeter(source.getRadiusMeter());
        result.setBudgetMax(source.getBudgetMax());
        result.setMinScore(source.getMinScore());
        result.setOpenAt(source.getOpenAt());
        result.setScene(source.getScene());
        result.setNeedVoucher(source.getNeedVoucher());
        return result;
    }

    public AgentModels.Intent normalize(AgentModels.Intent intent) {
        if (intent == null) intent = new AgentModels.Intent();
        if (intent.getIntent() == null || !INTENTS.contains(intent.getIntent())) intent.setIntent("SHOP_RECOMMENDATION");
        intent.setKeyword(KeywordNormalizer.normalize(clean(intent.getKeyword(), 40)));
        intent.setLocation(clean(intent.getLocation(), 40));
        intent.setRadiusMeter(inRange(intent.getRadiusMeter(), 100, 50000));
        intent.setBudgetMax(inRange(intent.getBudgetMax(), 0, 100000));
        intent.setMinScore(inRange(intent.getMinScore(), 0, 5));
        intent.setLatitude(inRange(intent.getLatitude(), -90, 90));
        intent.setLongitude(inRange(intent.getLongitude(), -180, 180));
        if (intent.getOpenAt() != null) {
            try {
                intent.setOpenAt(LocalTime.parse(intent.getOpenAt().trim(), TIME).format(TIME));
            } catch (DateTimeParseException e) {
                intent.setOpenAt(null);
            }
        }
        if (intent.getScene() != null && !SCENES.contains(intent.getScene().trim())) intent.setScene(null);
        if (intent.getScene() != null) intent.setScene(intent.getScene().trim());
        return intent;
    }

    private static Integer inRange(Integer value, int min, int max) {
        return value == null || value < min || value > max ? null : value;
    }

    private static Double inRange(Double value, double min, double max) {
        return value == null || !Double.isFinite(value) || value < min || value > max ? null : value;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result.substring(0, Math.min(result.length(), maxLength));
    }
}
