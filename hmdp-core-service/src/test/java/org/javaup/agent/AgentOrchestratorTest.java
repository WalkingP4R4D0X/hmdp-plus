package org.javaup.agent;

import org.javaup.agent.model.AgentModels;
import org.javaup.agent.ranking.ShopRankingService;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorTest {
    @Test
    void rankingFiltersBudgetAndBuildsEvidence() {
        Shop ok = new Shop().setId(1L).setName("日料店").setAvgPrice(80L).setScore(45).setOpenHours("10:00-22:00");
        Shop expensive = new Shop().setId(2L).setName("贵店").setAvgPrice(180L).setScore(50).setOpenHours("10:00-22:00");
        AgentModels.Intent intent = new AgentModels.Intent(); intent.setBudgetMax(100);
        List<AgentModels.ShopCard> result = new ShopRankingService().rank(List.of(ok, expensive), intent);
        assertEquals(1, result.size());
        assertEquals("日料店", result.get(0).getName());
        assertTrue(result.get(0).getReason().contains("人均约80元"));
    }

    @Test
    void rankingMarksMissingBusinessFields() {
        Shop shop = new Shop().setId(1L).setName("待完善店");
        AgentModels.Intent intent = new AgentModels.Intent();
        AgentModels.ShopCard card = new ShopRankingService().rank(List.of(shop), intent).get(0);
        assertTrue(card.getMissingData());
        assertNull(card.getScore());
    }

    @Test
    void requestedOpeningTimeIsAHardFilter() {
        Shop closed = new Shop().setId(1L).setName("晚间休息").setOpenHours("10:00-18:00");
        Shop open = new Shop().setId(2L).setName("营业中").setOpenHours("10:00-22:00");
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setOpenAt("21:00");

        List<AgentModels.ShopCard> result = new ShopRankingService().rank(List.of(closed, open), intent);

        assertEquals(1, result.size());
        assertEquals("营业中", result.get(0).getName());
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
}
