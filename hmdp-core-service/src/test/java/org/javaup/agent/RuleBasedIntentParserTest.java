package org.javaup.agent;

import org.javaup.agent.model.AgentModels;
import org.javaup.agent.service.RuleBasedIntentParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedIntentParserTest {
    @Test
    void parsesStructuredConstraints() {
        AgentModels.Intent intent = RuleBasedIntentParser.parseText("拱墅区人均100以内适合约会的日料，3公里内晚上9点营业，评分不低于4.5");
        assertEquals("日料", intent.getKeyword());
        assertEquals("拱墅区", intent.getLocation());
        assertEquals(100, intent.getBudgetMax());
        assertEquals(3000, intent.getRadiusMeter());
        assertEquals("21:00", intent.getOpenAt());
        assertEquals(4.5, intent.getMinScore());
        assertEquals("约会", intent.getScene());
    }

    @Test
    void mergesOnlyMissingFieldsForFollowUp() {
        RuleBasedIntentParser parser = new RuleBasedIntentParser();
        AgentModels.Intent first = RuleBasedIntentParser.parseText("西湖区人均100以内的火锅");
        AgentModels.Message history = new AgentModels.Message();
        history.setFilters(first);
        AgentModels.Intent followUp = parser.parse("预算150以内", List.of(history));
        assertEquals("火锅", followUp.getKeyword());
        assertEquals("西湖区", followUp.getLocation());
        assertEquals(150, followUp.getBudgetMax());
        assertTrue(followUp.getRadiusMeter() == null);
    }
}
