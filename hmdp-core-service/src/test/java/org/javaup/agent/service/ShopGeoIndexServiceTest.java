package org.javaup.agent.service;

import org.javaup.entity.Shop;
import org.javaup.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ShopGeoIndexServiceTest {
    @Test
    void rebuildsOnlyLocatedShopsIntoTypeKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        IShopService shops = mock(IShopService.class);
        ShopGeoIndexService service = new ShopGeoIndexService();
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "shopService", shops);
        org.mockito.Mockito.when(redis.opsForGeo()).thenReturn(geo);
        org.mockito.Mockito.when(redis.keys("shop:geo:*")).thenReturn(java.util.Set.of());
        org.mockito.Mockito.when(shops.list()).thenReturn(List.of(
                new Shop().setId(1L).setTypeId(1L).setX(120.15).setY(30.32),
                new Shop().setId(2L).setTypeId(1L)));

        service.rebuildAll();

        verify(geo).add(eq("shop:geo:1"), any(List.class));
        verify(geo, never()).add(eq("shop:geo:2"), any(List.class));
        org.junit.jupiter.api.Assertions.assertEquals(ShopGeoIndexService.IndexState.READY, service.getState());
    }

    @Test
    void marksIndexUnavailableWhenRebuildFails() {
        ShopGeoIndexService service = new ShopGeoIndexService();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        IShopService shops = mock(IShopService.class);
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "shopService", shops);
        org.mockito.Mockito.when(shops.list()).thenThrow(new IllegalStateException("database unavailable"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::rebuildAll);
        service.initializeOnApplicationReady();

        org.junit.jupiter.api.Assertions.assertEquals(ShopGeoIndexService.IndexState.UNAVAILABLE, service.getState());
    }
}
