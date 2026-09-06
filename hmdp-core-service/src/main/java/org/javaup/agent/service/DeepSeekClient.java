package org.javaup.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal OpenAI-compatible DeepSeek client used only for structured intent parsing. */
@Slf4j
@Component
public class DeepSeekClient {
    private final ObjectMapper objectMapper;
    private final ObjectMapper llmObjectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;

    public DeepSeekClient(
            ObjectMapper objectMapper,
            @Value("${agent.llm.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agent.llm.api-key:}") String apiKey,
            @Value("${agent.llm.model:deepseek-chat}") String model,
            @Value("${agent.llm.read-timeout:8s}") Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.llmObjectMapper = objectMapper.copy().disable(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS);
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public AgentModels.Intent parseIntent(String message, List<AgentModels.Message> history) {
        if (!isConfigured()) {
            log.info("agent_llm_intent_skipped reason=api_key_not_configured");
            return null;
        }
        try {
            log.info("agent_llm_intent_start model={} messageLength={} historySize={}", model, message.length(), history == null ? 0 : history.size());
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", 300);
            body.put("response_format", Map.of("type", "json_object"));
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt()));
            if (history != null) {
                history.stream().skip(Math.max(0, history.size() - 6L)).forEach(item -> {
                    if (item.getContent() != null && !item.getContent().isBlank()) {
                        messages.add(Map.of("role", normalizeRole(item.getRole()), "content", item.getContent()));
                    }
                });
            }
            messages.add(Map.of("role", "user", "content", message));
            body.put("messages", messages);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(llmObjectMapper.writeValueAsString(body)))
                    .build();
            AgentModels.Intent intent = parseIntentJson(send(request));
            log.info("agent_llm_intent_success model={} intent={}", model, intent.getIntent());
            return intent;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek request interrupted", e);
        } catch (Exception e) {
            log.warn("agent_llm_intent_failed model={} reason={}", model, e.getMessage());
            throw new IllegalStateException("DeepSeek request failed: " + e.getMessage(), e);
        }
    }

    /** Generate prose only from the already validated business result. */
    public String explain(AgentModels.Intent intent, List<AgentModels.ShopCard> cards) {
        if (!isConfigured()) {
            log.info("agent_llm_explain_skipped reason=api_key_not_configured");
            return null;
        }
        if (cards == null || cards.isEmpty()) return null;
        try {
            log.info("agent_llm_explain_start model={} cards={}", model, cards.size());
            Map<String, Object> payload = Map.of("filters", intent, "shops", cards);
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("temperature", 0.2);
            body.put("max_tokens", 500);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "你是黑马点评导购助手。只能依据给定的已核验商户数据回答，不能修改或补造价格、距离、评分、营业状态或优惠券信息。用简洁中文说明推荐理由，不要输出 JSON。"),
                    Map.of("role", "user", "content", "用户筛选条件和商户结果如下：" + llmObjectMapper.writeValueAsString(payload))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(requestTimeout).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(llmObjectMapper.writeValueAsString(body))).build();
            String answer = send(request);
            log.info("agent_llm_explain_success model={} cards={}", model, cards.size());
            return answer == null ? null : answer.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("agent_llm_explain_failed model={} reason={}", model, e.getMessage());
            return null;
        }
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = diagnosticBody(response.body());
            String contentType = response.headers().firstValue("Content-Type").orElse("unknown");
            log.warn("agent_llm_http_error status={} contentType={} body={}", response.statusCode(), contentType, body);
            throw new IllegalStateException("DeepSeek returned HTTP " + response.statusCode()
                    + " contentType=" + contentType + " body=" + body);
        }
        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) throw new IllegalStateException("DeepSeek response has no message content");
        return content;
    }

    private String diagnosticBody(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        String value = body.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (apiKey != null && !apiKey.isBlank()) value = value.replace(apiKey, "<redacted>");
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }

    public static AgentModels.Intent parseIntentJson(String content) throws Exception {
        String json = content.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        ObjectMapper mapper = new ObjectMapper();
        AgentModels.Intent intent = mapper.readValue(json, AgentModels.Intent.class);
        if (intent.getIntent() == null || intent.getIntent().isBlank()) {
            intent.setIntent("SHOP_RECOMMENDATION");
        }
        return intent;
    }

    private String endpoint() {
        String url = baseUrl == null ? "https://api.deepseek.com" : baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.endsWith("/chat/completions") ? url : url + "/chat/completions";
    }

    private String normalizeRole(String role) {
        return "assistant".equals(role) ? "assistant" : "user";
    }

    private String systemPrompt() {
        return "你是商户搜索意图解析器。只输出 JSON，不要解释，不要调用工具。字段必须是 "
                + "intent,keyword,location,latitude,longitude,radiusMeter,budgetMax,minScore,openAt,scene,needVoucher。"
                + "无法确定的字段使用 null。latitude/longitude 不要猜测用户位置。";
    }
}
