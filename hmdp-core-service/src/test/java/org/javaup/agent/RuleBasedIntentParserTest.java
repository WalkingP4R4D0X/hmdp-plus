package org.javaup.agent;

import org.javaup.agent.model.AgentModels;
import org.javaup.agent.service.RuleBasedIntentParser;
import org.javaup.agent.service.DeepSeekClient;
import org.javaup.agent.service.IntentNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.net.InetSocketAddress;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.sun.net.httpserver.HttpServer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void genericFoodWordingDoesNotBecomeANameKeyword() {
        AgentModels.Intent intent = RuleBasedIntentParser.parseText("附近有什么好吃的");
        assertNull(intent.getKeyword());
        assertEquals(3000, intent.getRadiusMeter());
    }

    @Test
    void normalizesShopSuffixInModelIntent() {
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setKeyword("火锅店");

        new IntentNormalizer().normalize(intent);

        assertEquals("火锅", intent.getKeyword());
    }

    @Test
    void greetingDoesNotBecomeAShopRecommendation() {
        RuleBasedIntentParser parser = new RuleBasedIntentParser();
        AgentModels.Intent intent = parser.parse("你好！", List.of());
        assertEquals("GREETING", intent.getIntent());
        assertNull(intent.getKeyword());
    }

    @Test
    void gibberishIsRejectedBeforeCallingTheModel() {
        RuleBasedIntentParser parser = new RuleBasedIntentParser();
        AgentModels.Intent intent = parser.parse("asdasdfa", List.of());
        assertEquals("INVALID_INPUT", intent.getParseStatus());
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

    @Test
    void parsesDeepSeekStructuredIntentJson() throws Exception {
        AgentModels.Intent intent = DeepSeekClient.parseIntentJson(
                "```json\n{\"keyword\":\"日料\",\"budgetMax\":150,\"needVoucher\":true}\n``` ");
        assertEquals("SHOP_RECOMMENDATION", intent.getIntent());
        assertEquals("日料", intent.getKeyword());
        assertEquals(150, intent.getBudgetMax());
        assertTrue(intent.getNeedVoucher());
    }

    @Test
    void callsOpenAiCompatibleDeepSeekEndpoint() throws Exception {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (java.io.IOException e) {
            // Some CI sandboxes prohibit loopback sockets; the unit contract test remains valid there.
            return;
        }
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"keyword\\\":\\\"火锅\\\",\\\"budgetMax\\\":120}\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper applicationMapper = new ObjectMapper()
                    .enable(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS);
            org.javaup.agent.service.DeepSeekClient client = new org.javaup.agent.service.DeepSeekClient(
                    applicationMapper, "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "deepseek-chat", Duration.ofSeconds(2));
            AgentModels.Intent intent = client.parseIntent("找火锅", List.of());
            assertEquals("火锅", intent.getKeyword());
            assertEquals(120, intent.getBudgetMax());
            com.fasterxml.jackson.databind.JsonNode request = new ObjectMapper().readTree(requestBody.get());
            assertTrue(request.path("max_tokens").isNumber());
            assertTrue(request.path("temperature").isNumber());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesProviderErrorBodyForHttp400() throws Exception {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (java.io.IOException e) {
            // Some CI sandboxes prohibit loopback sockets.
            return;
        }
        server.createContext("/chat/completions", exchange -> {
            byte[] body = "{\"error\":{\"message\":\"invalid response_format\",\"type\":\"invalid_request_error\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekClient client = new DeepSeekClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "deepseek-chat", Duration.ofSeconds(2));
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> client.parseIntent("找火锅", List.of()));
            assertTrue(error.getMessage().contains("HTTP 400"));
            assertTrue(error.getMessage().contains("invalid response_format"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void normalizesModelBoundsAndUnknownFields() {
        AgentModels.Intent intent = new AgentModels.Intent();
        intent.setKeyword("  ".repeat(30));
        intent.setRadiusMeter(50);
        intent.setBudgetMax(200000);
        intent.setMinScore(7.0);
        intent.setOpenAt("not-a-time");
        intent.setScene("未知场景");

        new IntentNormalizer().normalize(intent);

        assertNull(intent.getKeyword());
        assertNull(intent.getRadiusMeter());
        assertNull(intent.getBudgetMax());
        assertNull(intent.getMinScore());
        assertNull(intent.getOpenAt());
        assertNull(intent.getScene());
    }
}
