package org.javaup.agent.tool;

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

import static org.javaup.utils.RedisConstants.SHOP_GEO_KEY;

@Component
public class NearbyShopTool implements AgentTool<AgentModels.Intent, List<Shop>> {
    private static final int MAX_SHOP_TYPE_ID = 30;

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
        for (int typeId = 1; typeId <= MAX_SHOP_TYPE_ID; typeId++) {
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
        List<Shop> shops = shopService.listByIds(distances.keySet());
        shops.forEach(shop -> shop.setDistance(distances.get(shop.getId())));
        shops.sort(Comparator.comparing(Shop::getDistance, Comparator.nullsLast(Double::compareTo)));
        return new ArrayList<>(shops);
    }
}
