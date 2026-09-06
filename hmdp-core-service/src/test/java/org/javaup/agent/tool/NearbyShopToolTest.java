package org.javaup.agent.tool;

import org.javaup.entity.Shop;
import org.junit.jupiter.api.Test;

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
}
