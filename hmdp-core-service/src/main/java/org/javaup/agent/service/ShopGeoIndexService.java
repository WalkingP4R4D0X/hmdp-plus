package org.javaup.agent.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.Shop;
import org.javaup.service.IShopService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.javaup.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class ShopGeoIndexService {
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private IShopService shopService;

    private volatile IndexState state = IndexState.NOT_READY;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeOnApplicationReady() {
        try {
            rebuildAll();
        } catch (RuntimeException e) {
            state = IndexState.UNAVAILABLE;
            log.error("shop_geo_index_initialize_failed", e);
        }
    }

    /** Rebuilds all shop GEO keys from authoritative MySQL data. */
    public synchronized void rebuildAll() {
        List<Shop> shops = shopService.list();
        Set<String> existingKeys = redis.keys(SHOP_GEO_KEY + "*");
        if (existingKeys != null && !existingKeys.isEmpty()) {
            redis.delete(existingKeys);
        }

        Map<Long, List<Shop>> shopsByType = shops.stream()
                .filter(this::hasCoordinates)
                .collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : shopsByType.entrySet()) {
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(entry.getValue().size());
            for (Shop shop : entry.getValue()) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        String.valueOf(shop.getId()), new Point(shop.getX(), shop.getY())));
            }
            redis.opsForGeo().add(SHOP_GEO_KEY + entry.getKey(), locations);
        }
        state = IndexState.READY;
        log.info("shop_geo_index_ready shopCount={} typeCount={}",
                shopsByType.values().stream().mapToInt(List::size).sum(), shopsByType.size());
    }

    public IndexState getState() {
        return state;
    }

    public void markUnavailable() {
        state = IndexState.UNAVAILABLE;
    }

    private boolean hasCoordinates(Shop shop) {
        return shop.getId() != null && shop.getTypeId() != null
                && shop.getX() != null && shop.getY() != null;
    }

    public enum IndexState {
        NOT_READY,
        READY,
        UNAVAILABLE
    }
}
