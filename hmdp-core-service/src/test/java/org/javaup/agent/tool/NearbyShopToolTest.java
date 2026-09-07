package org.javaup.agent.tool;

import org.javaup.agent.model.AgentModels;
import org.javaup.agent.service.ShopGeoIndexService;
import org.javaup.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NearbyShopToolTest {
    @Test
    void sortsAnImmutableCandidateListByDistance() {
        List<Shop> candidates = List.of(
                new Shop().setId(1L).setDistance(1200d),
                new Shop().setId(2L).setDistance(300d));

        List<Shop> sorted = NearbyShopTool.sortByDistance(candidates);

        assertEquals(List.of(2L, 1L), sorted.stream().map(Shop::getId).toList());
    }

    @Test
    void convertsRedisKilometersToMetersAtTheToolBoundary() {
        assertEquals(1250D, NearbyShopTool.distanceInMeters(new Distance(1.25D, Metrics.KILOMETERS)));
    }

    @Test
    void reportsMissingLocationBeforeTouchingInfrastructure() {
        NearbyShopTool tool = new NearbyShopTool();
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setRadiusMeter(3000);

        NearbySearchResult result = tool.executeDetailed(intent, null);

        assertEquals(NearbySearchResult.Status.NO_LOCATION, result.status());
    }

    @Test
    void reportsIndexNotReadyBeforeQueryingRedis() {
        NearbyShopTool tool = new NearbyShopTool();
        ReflectionTestUtils.setField(tool, "geoIndexService", new ShopGeoIndexService());
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setLatitude(30.32);
        intent.setLongitude(120.15);
        intent.setRadiusMeter(3000);

        NearbySearchResult result = tool.executeDetailed(intent, null);

        assertEquals(NearbySearchResult.Status.INDEX_EMPTY, result.status());
    }
}
