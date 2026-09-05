package org.javaup.agent.service;

import org.javaup.agent.model.AgentModels;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Applies server-side bounds and defaults to model-produced intent fields. */
@Component
public class IntentNormalizer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> SCENES = Set.of("约会", "聚餐", "亲子", "拍照", "夜宵");

    public AgentModels.Intent normalize(AgentModels.Intent intent) {
        if (intent == null) intent = new AgentModels.Intent();
        if (!"SHOP_RECOMMENDATION".equals(intent.getIntent())) intent.setIntent("SHOP_RECOMMENDATION");
        intent.setKeyword(clean(intent.getKeyword(), 40));
        intent.setLocation(clean(intent.getLocation(), 40));
        intent.setRadiusMeter(inRange(intent.getRadiusMeter(), 100, 50000));
        intent.setBudgetMax(inRange(intent.getBudgetMax(), 0, 100000));
        if (intent.getMinScore() != null && (intent.getMinScore() < 0 || intent.getMinScore() > 5)) intent.setMinScore(null);
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

    private static String clean(String value, int maxLength) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result.substring(0, Math.min(result.length(), maxLength));
    }
}
