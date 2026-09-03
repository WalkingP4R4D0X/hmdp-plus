package org.javaup.agent;

import org.javaup.agent.model.AgentModels;
import org.javaup.agent.ranking.ShopRankingService;
import org.javaup.entity.Shop;
import org.junit.jupiter.api.Test;
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
}
