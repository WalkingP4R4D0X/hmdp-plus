package org.javaup.agent.tool;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import org.javaup.entity.Shop;
import org.javaup.service.IShopService;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import static org.javaup.utils.RedisConstants.SHOP_GEO_KEY;

@Component
public class NearbyShopTool implements AgentTool<AgentModels.Intent, List<Shop>> {
    private static final int MAX_SHOP_TYPE_ID = 30;
    private static final long FOOD_TYPE_ID = 1L;

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private IShopService shopService;

    @Override
    public String name() {
        return "searchNearbyShops";
    }

    @Override
    public List<Shop> execute(AgentModels.Intent input, AgentContext context) {
        if (input.getLatitude() == null || input.getLongitude() == null || input.getRadiusMeter() == null) {
            return List.of();
        }
        Map<Long, Double> distances = new HashMap<>();
        for (long typeId : geoTypeIds(input)) {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                    SHOP_GEO_KEY + typeId,
                    GeoReference.fromCoordinate(input.getLongitude(), input.getLatitude()),
                    new Distance(input.getRadiusMeter()),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(10));
            if (results == null) {
                continue;
            }
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                try {
                    distances.put(Long.valueOf(result.getContent().getName()), result.getDistance().getValue());
                } catch (NumberFormatException ignored) {
                    // Ignore malformed GEO members instead of trusting them as shop identifiers.
                }
            }
        }
        if (distances.isEmpty()) {
            return List.of();
        }
        List<Shop> shops = shopService.listByIds(distances.keySet()).stream()
                .filter(shop -> matchesKeywordAndLocation(shop, input))
                .toList();
        shops.forEach(shop -> shop.setDistance(distances.get(shop.getId())));
        shops.sort(Comparator.comparing(Shop::getDistance, Comparator.nullsLast(Double::compareTo)));
        return new ArrayList<>(shops);
    }

    private static Set<Long> geoTypeIds(AgentModels.Intent input) {
        String keyword = normalizeKeyword(input.getKeyword());
        if (keyword == null || isFoodKeyword(keyword)) return Set.of(FOOD_TYPE_ID);
        Long typeId = typeIdForKeyword(keyword);
        if (typeId != null) return Set.of(typeId);
        Set<Long> all = new LinkedHashSet<>();
        for (long candidateTypeId = 1; candidateTypeId <= MAX_SHOP_TYPE_ID; candidateTypeId++) {
            all.add(candidateTypeId);
        }
        return all;
    }

    public static boolean matchesKeywordAndLocation(Shop shop, AgentModels.Intent input) {
        String keyword = normalizeKeyword(input.getKeyword());
        boolean keywordMatches = keyword == null || matchesKeywordOrType(shop, keyword);
        String location = normalizeKeyword(input.getLocation());
        boolean locationMatches = location == null
                || contains(shop.getArea(), location)
                || contains(shop.getAddress(), location);
        return keywordMatches && locationMatches;
    }

    private static boolean contains(String value, String needle) {
        return StrUtil.isNotBlank(value) && StrUtil.isNotBlank(needle) && value.contains(needle);
    }

    private static String normalizeKeyword(String keyword) {
        return StrUtil.isBlank(keyword) ? null : keyword.trim();
    }

    private static boolean matchesKeywordOrType(Shop shop, String keyword) {
        Long typeId = typeIdForKeyword(keyword);
        if (typeId != null && isBroadFoodKeyword(keyword)) {
            return typeId.equals(shop.getTypeId());
        }
        if (typeId != null && !isFoodKeyword(keyword)) {
            return typeId.equals(shop.getTypeId()) || aliases(keyword).stream().anyMatch(a -> contains(shop.getName(), a));
        }
        return aliases(keyword).stream().anyMatch(a -> contains(shop.getName(), a));
    }

    private static boolean isFoodKeyword(String keyword) {
        return Set.of("美食", "餐厅", "吃饭", "好吃的", "日料", "寿司", "刺身", "火锅", "咖啡", "烧烤", "甜品")
                .contains(keyword);
    }

    private static boolean isBroadFoodKeyword(String keyword) {
        return Set.of("美食", "餐厅", "吃饭", "好吃的").contains(keyword);
    }

    /** Maps top-level shop types to the IDs used by tb_shop/tb_shop_type. */
    public static Long typeIdForKeyword(String keyword) {
        if (keyword == null) return null;
        return switch (keyword.toLowerCase()) {
            case "美食", "餐厅", "吃饭", "好吃的", "日料", "寿司", "刺身", "火锅", "咖啡", "烧烤", "甜品" -> 1L;
            case "ktv", "唱歌" -> 2L;
            case "丽人", "美发" -> 3L;
            case "健身", "运动" -> 4L;
            case "按摩", "足疗" -> 5L;
            case "美容", "spa" -> 6L;
            case "亲子", "亲子游乐" -> 7L;
            case "酒吧" -> 8L;
            case "轰趴", "轰趴馆" -> 9L;
            case "美睫", "美甲" -> 10L;
            default -> null;
        };
    }

    private static Set<String> aliases(String keyword) {
        return switch (keyword) {
            case "日料", "刺身" -> Set.of(keyword, "日料", "寿司", "刺身");
            case "寿司" -> Set.of("寿司", "日料", "刺身");
            case "火锅" -> Set.of("火锅", "涮锅", "羊蝎子");
            case "ktv", "KTV", "唱歌" -> Set.of("KTV", "唱歌");
            default -> Set.of(keyword);
        };
    }
}
