package org.javaup.agent.tool;

import org.javaup.agent.model.AgentModels;
import org.javaup.agent.service.IntentNormalizer;
import org.javaup.agent.service.ShopGeoIndexService;
import org.javaup.entity.Shop;
import org.javaup.service.IShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class NearbyShopRedisGeoIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;

    @AfterEach
    void closeRedisClient() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void searchesRealRedisGeoAndReturnsDistancesInMeters() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        Shop hotpot = new Shop().setId(5L).setTypeId(1L).setName("海底捞火锅")
                .setX(120.15778).setY(30.310633);
        Shop farAway = new Shop().setId(99L).setTypeId(1L).setName("远处火锅店")
                .setX(120.25).setY(30.42);
        List<Shop> shops = List.of(hotpot, farAway);
        IShopService shopService = mock(IShopService.class);
        when(shopService.list()).thenReturn(shops);
        when(shopService.listByIds(anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return shops.stream().filter(shop -> ids.contains(shop.getId())).toList();
        });

        ShopGeoIndexService indexService = new ShopGeoIndexService();
        ReflectionTestUtils.setField(indexService, "redis", redis);
        ReflectionTestUtils.setField(indexService, "shopService", shopService);
        indexService.rebuildAll();

        NearbyShopTool tool = new NearbyShopTool();
        ReflectionTestUtils.setField(tool, "redis", redis);
        ReflectionTestUtils.setField(tool, "shopService", shopService);
        ReflectionTestUtils.setField(tool, "geoIndexService", indexService);
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setKeyword("火锅店");
        intent.setLatitude(30.32);
        intent.setLongitude(120.15);
        intent.setRadiusMeter(3000);
        new IntentNormalizer().normalize(intent);

        NearbySearchResult result = tool.executeDetailed(intent, null);

        assertEquals("火锅", intent.getKeyword());
        assertEquals(NearbySearchResult.Status.SUCCESS, result.status());
        assertEquals(List.of(5L), result.shops().stream().map(Shop::getId).toList());
        assertTrue(result.shops().get(0).getDistance() > 1000D);
        assertTrue(result.shops().get(0).getDistance() < 1500D);
    }
}
