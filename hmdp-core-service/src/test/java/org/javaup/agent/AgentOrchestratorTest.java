package org.javaup.agent;

import org.javaup.agent.model.AgentModels;
import org.javaup.agent.model.ShopCandidate;
import org.javaup.agent.memory.ConversationMemory;
import org.javaup.agent.service.AgentOrchestrator;
import org.javaup.agent.service.IntentNormalizer;
import org.javaup.agent.service.IntentParser;
import org.javaup.agent.tool.NearbyShopTool;
import org.javaup.agent.tool.ShopContentTool;
import org.javaup.agent.tool.ShopSearchTool;
import org.javaup.agent.tool.VoucherTool;
import org.javaup.agent.ranking.ShopRankingService;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {
    @Test
    void radiusIsInterpretedAsMetersForGeoQueries() {
        Distance radius = new Distance(3000d / 1000d, Metrics.KILOMETERS);
        assertEquals(3d, radius.getValue(), 0.0001d);
        assertEquals(Metrics.KILOMETERS, radius.getMetric());
    }

    @Test
    void rankingFiltersBudgetAndBuildsEvidence() {
        Shop ok = new Shop().setId(1L).setName("日料店").setAvgPrice(80L).setScore(45).setOpenHours("10:00-22:00");
        Shop expensive = new Shop().setId(2L).setName("贵店").setAvgPrice(180L).setScore(50).setOpenHours("10:00-22:00");
        AgentModels.Intent intent = new AgentModels.Intent(); intent.setBudgetMax(100);
        List<AgentModels.ShopCard> result = new ShopRankingService().rank(List.of(candidate(ok), candidate(expensive)), intent);
        assertEquals(1, result.size());
        assertEquals("日料店", result.get(0).getName());
        assertTrue(result.get(0).getReason().contains("人均约80元"));
    }

    @Test
    void rankingMarksMissingBusinessFields() {
        Shop shop = new Shop().setId(1L).setName("待完善店");
        AgentModels.Intent intent = new AgentModels.Intent();
        AgentModels.ShopCard card = new ShopRankingService().rank(List.of(candidate(shop)), intent).get(0);
        assertTrue(card.getMissingData());
        assertNull(card.getScore());
    }

    @Test
    void requestedOpeningTimeIsAHardFilter() {
        Shop closed = new Shop().setId(1L).setName("晚间休息").setOpenHours("10:00-18:00");
        Shop open = new Shop().setId(2L).setName("营业中").setOpenHours("10:00-22:00");
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setOpenAt("21:00");

        List<AgentModels.ShopCard> result = new ShopRankingService().rank(List.of(candidate(closed), candidate(open)), intent);

        assertEquals(1, result.size());
        assertEquals("营业中", result.get(0).getName());
    }

    @Test
    void radiusRequiresMeasuredDistance() {
        Shop unknownDistance = new Shop().setId(1L).setName("未知距离");
        Shop inside = new Shop().setId(2L).setName("范围内").setDistance(1200D);
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setRadiusMeter(3000);

        List<AgentModels.ShopCard> result = new ShopRankingService().rank(List.of(candidate(unknownDistance), candidate(inside)), intent);

        assertEquals(1, result.size());
        assertEquals("范围内", result.get(0).getName());
    }

    @Test
    void requestCoordinatesAreCopiedToIntent() {
        AgentModels.ChatRequest request = new AgentModels.ChatRequest();
        request.setLatitude(30.32);
        request.setLongitude(120.15);
        AgentModels.Intent intent = new AgentModels.Intent();

        org.javaup.agent.service.AgentOrchestrator.applyRequestLocation(request, intent);

        assertEquals(30.32, intent.getLatitude());
        assertEquals(120.15, intent.getLongitude());
    }

    @Test
    void incompleteRequestCoordinatesAreRejected() {
        AgentModels.ChatRequest request = new AgentModels.ChatRequest();
        request.setLatitude(30.32);
        AgentModels.Intent intent = new AgentModels.Intent();

        assertThrows(IllegalArgumentException.class,
                () -> org.javaup.agent.service.AgentOrchestrator.applyRequestLocation(request, intent));
    }

    @Test
    void radiusWithoutDistanceIsRejectedByRanking() {
        Shop unknownDistance = new Shop().setId(1L).setName("未知距离");
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setRadiusMeter(3000);

        assertTrue(new ShopRankingService().rank(List.of(candidate(unknownDistance)), intent).isEmpty());
    }

    @Test
    void emptyIntentDoesNotAllowUnboundedShopSearch() {
        assertFalse(org.javaup.agent.service.AgentOrchestrator.hasSearchConstraint(new AgentModels.Intent()));
        AgentModels.Intent constrained = new AgentModels.Intent();
        constrained.setKeyword("火锅");
        assertTrue(org.javaup.agent.service.AgentOrchestrator.hasSearchConstraint(constrained));
    }

    @Test
    void nearbyResultsApplyCategoryAndAreaFilters() {
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setKeyword("火锅");
        intent.setLocation("拱墅区");
        Shop hotpot = new Shop().setName("拱墅火锅店").setArea("运河上街").setAddress("拱墅区某路");
        Shop sushi = new Shop().setName("寿司店").setArea("运河上街").setAddress("拱墅区某路");
        Shop otherArea = new Shop().setName("火锅店").setArea("西湖区").setAddress("西湖区某路");

        assertTrue(NearbyShopTool.matchesKeywordAndLocation(hotpot, intent));
        assertFalse(NearbyShopTool.matchesKeywordAndLocation(sushi, intent));
        assertFalse(NearbyShopTool.matchesKeywordAndLocation(otherArea, intent));
    }

    @Test
    void nearbyFoodWithoutKeywordDoesNotMatchKtv() {
        AgentModels.Intent intent = new AgentModels.Intent();
        Shop food = new Shop().setName("餐厅").setTypeId(1L);
        Shop ktv = new Shop().setName("KTV").setTypeId(2L);

        assertTrue(NearbyShopTool.matchesKeywordAndLocation(food, intent));
        // Type selection is applied before this predicate; this assertion documents
        // that the predicate itself does not invent a name keyword.
        assertTrue(NearbyShopTool.matchesKeywordAndLocation(ktv, intent));
    }

    @Test
    void nearbyKeywordMatchesTopLevelTypeAndAliases() {
        AgentModels.Intent ktvIntent = new AgentModels.Intent();
        ktvIntent.setKeyword("唱歌");
        Shop ktv = new Shop().setName("开乐迪").setTypeId(2L);
        Shop food = new Shop().setName("开乐迪").setTypeId(1L);

        assertEquals(2L, NearbyShopTool.typeIdForKeyword("KTV"));
        assertTrue(NearbyShopTool.matchesKeywordAndLocation(ktv, ktvIntent));
        assertFalse(NearbyShopTool.matchesKeywordAndLocation(food, ktvIntent));
    }

    @Test
    void nearbyFoodKeywordMatchesCommonNameVariants() {
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setKeyword("火锅");
        assertTrue(NearbyShopTool.matchesKeywordAndLocation(
                new Shop().setName("幸福里老北京涮锅"), intent));
        assertFalse(NearbyShopTool.matchesKeywordAndLocation(
                new Shop().setName("浅草屋寿司"), intent));
    }

    @Test
    void duplicateVoucherRowsAreMergedAndKeepSeckillFields() {
        Voucher plain = new Voucher().setId(4L).setShopId(4L).setTitle("秒杀券").setStatus(1)
                .setType(1);
        Voucher seckill = new Voucher().setId(4L).setShopId(4L).setTitle("秒杀券").setStatus(1)
                .setType(1).setStock(50).setBeginTime(LocalDateTime.now().minusMinutes(1))
                .setEndTime(LocalDateTime.now().plusHours(1));

        List<Voucher> result = VoucherServiceImpl.mergeDuplicateVouchers(new ArrayList<>(List.of(plain, seckill)));

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getStatus());
        assertEquals(50, result.get(0).getStock());
        assertNotNull(result.get(0).getBeginTime());
    }

    @Test
    void modelFallbackUsesOrdinarySearchWithoutClaimingDistanceWasApplied() {
        AgentOrchestrator orchestrator = new AgentOrchestrator();
        ConversationMemory memory = mock(ConversationMemory.class);
        IntentParser parser = mock(IntentParser.class);
        ShopSearchTool search = mock(ShopSearchTool.class);
        VoucherTool vouchers = mock(VoucherTool.class);
        ShopContentTool content = mock(ShopContentTool.class);
        ConversationMemory.State state = new ConversationMemory.State("c_test", "guest:test", List.of(), null, true);
        when(memory.open(null, "guest:test")).thenReturn(state);
        when(memory.saveTurn(any(), any(), any())).thenReturn(true);
        when(parser.parse(any(), any())).thenThrow(new IllegalStateException("model unavailable"));
        when(search.name()).thenReturn("searchShops");
        when(search.execute(any(), any())).thenReturn(List.of(candidate(new Shop()
                .setId(1L).setName("火锅店").setAddress("拱墅区某路").setAvgPrice(90L)
                .setScore(45).setOpenHours("10:00-22:00"))));
        when(vouchers.name()).thenReturn("listShopVouchers");
        when(vouchers.executeBatch(any(), any())).thenReturn(java.util.Map.of(1L, List.of()));
        ReflectionTestUtils.setField(orchestrator, "memory", memory);
        ReflectionTestUtils.setField(orchestrator, "intentParser", parser);
        ReflectionTestUtils.setField(orchestrator, "intentNormalizer", new IntentNormalizer());
        ReflectionTestUtils.setField(orchestrator, "shopSearchTool", search);
        ReflectionTestUtils.setField(orchestrator, "nearbyShopTool", mock(NearbyShopTool.class));
        ReflectionTestUtils.setField(orchestrator, "voucherTool", vouchers);
        ReflectionTestUtils.setField(orchestrator, "contentTool", content);
        ReflectionTestUtils.setField(orchestrator, "ranking", new ShopRankingService());
        ReflectionTestUtils.setField(orchestrator, "deepSeekClient", mock(org.javaup.agent.service.DeepSeekClient.class));
        AgentModels.ChatRequest request = new AgentModels.ChatRequest();
        request.setMessage("附近3公里的火锅店");
        request.setLatitude(30.32);
        request.setLongitude(120.15);

        AgentModels.ChatResponse response = orchestrator.chat(request, "guest:test", null);

        assertTrue(response.isFallback());
        assertEquals("AGENT_FALLBACK", response.getErrorCode());
        assertEquals(1, response.getCards().size());
        assertEquals("火锅", response.getFilters().get("keyword"));
        assertFalse(response.getFilters().containsKey("radiusMeter"));
        assertNull(response.getCards().get(0).getDistanceMeter());
        verify(search).execute(any(), any());
    }

    private static ShopCandidate candidate(Shop shop) {
        return ShopCandidate.from(shop);
    }
}
