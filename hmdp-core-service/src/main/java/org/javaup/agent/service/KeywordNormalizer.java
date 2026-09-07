package org.javaup.agent.service;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.Map;

/** Canonicalizes user and model keywords before any shop search is routed. */
public final class KeywordNormalizer {
    private static final Map<String, String> SUFFIXES = Map.of(
            "火锅店", "火锅",
            "日料店", "日料",
            "寿司店", "寿司",
            "咖啡店", "咖啡",
            "烧烤店", "烧烤",
            "甜品店", "甜品",
            "餐馆", "餐厅",
            "饭店", "餐厅");

    private KeywordNormalizer() {
    }

    public static String normalize(String keyword) {
        if (StrUtil.isBlank(keyword)) return null;
        String normalized = keyword.trim();
        String mapped = SUFFIXES.get(normalized);
        if (mapped != null) return mapped;
        return normalized.toLowerCase(Locale.ROOT).equals("ktv") ? "KTV" : normalized;
    }
}
