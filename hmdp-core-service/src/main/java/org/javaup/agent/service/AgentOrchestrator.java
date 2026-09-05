package org.javaup.agent.service;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.agent.memory.ConversationMemory;
import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import org.javaup.agent.ranking.ShopRankingService;
import org.javaup.agent.tool.NearbyShopTool;
import org.javaup.agent.tool.ShopContentTool;
import org.javaup.agent.tool.ShopSearchTool;
import org.javaup.agent.tool.VoucherTool;
import org.javaup.entity.Shop;
import org.javaup.utils.UserHolder;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AgentOrchestrator {
    private static final int MAX_TOOL_CALLS = 5;
    @Resource private ConversationMemory memory;
    @Resource private IntentParser intentParser;
    @Resource private ShopSearchTool shopSearchTool;
    @Resource private NearbyShopTool nearbyShopTool;
    @Resource private VoucherTool voucherTool;
    @Resource private ShopContentTool contentTool;
    @Resource private ShopRankingService ranking;
    @Resource private DeepSeekClient deepSeekClient;
    @Resource private IntentNormalizer intentNormalizer;
    @Resource private MeterRegistry meterRegistry;

    public AgentModels.ChatResponse chat(AgentModels.ChatRequest request) {
        long started = System.currentTimeMillis();
        meterRegistry.counter("agent.request.total").increment();
        Timer.Sample timer = Timer.start(meterRegistry);
        AgentModels.ChatResponse response = new AgentModels.ChatResponse();
        response.setTraceId("trace-" + UUID.randomUUID());
        String conversationId = memory.ensureId(request.getConversationId());
        response.setConversationId(conversationId);
        AgentContext context = new AgentContext(UserHolder.getUser() == null ? null : UserHolder.getUser().getId(), conversationId);
        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
            if (!memory.exists(conversationId) || !memory.belongsTo(conversationId, context.getUserId())) { response.setErrorCode("AGENT_UNAUTHORIZED"); return response; }
        } else {
            memory.claim(conversationId, context.getUserId());
        }
        if (request.getMessage() == null || request.getMessage().isBlank() || request.getMessage().length() > 500) {
            response.setAnswer("请输入1-500字的查店需求"); response.setErrorCode("AGENT_REQUEST_INVALID"); return response;
        }
        try {
            AgentModels.Intent intent = intentNormalizer.normalize(intentParser.parse(request.getMessage(), memory.read(conversationId)));
            applyRequestLocation(request, intent);
            List<String> calls = new ArrayList<>();
            List<Shop> candidates = intent.getLatitude() != null && intent.getLongitude() != null
                    ? callNearby(intent, context, calls) : callSearch(intent, context, calls);
            List<AgentModels.ShopCard> cards = ranking.rank(candidates, intent);
            enrich(cards, intent, context, calls);
            response.setCards(cards); response.setFilters(filters(intent));
            String generatedAnswer = deepSeekClient.explain(intent, cards);
            response.setAnswer(StrUtil.isBlank(generatedAnswer) ? answer(cards) : generatedAnswer);
            if (cards.isEmpty()) meterRegistry.counter("agent.no_result.total").increment();
            persist(conversationId, request.getMessage(), intent, response.getAnswer(), calls);
            log.info("agent_request traceId={} conversationId={} tools={} cards={} latencyMs={}", response.getTraceId(), conversationId, calls, cards.size(), System.currentTimeMillis() - started);
            return response;
        } catch (Exception e) {
            log.warn("agent request failed traceId={}", response.getTraceId(), e);
            if (Thread.currentThread().isInterrupted()) {
                response.setErrorCode("AGENT_REQUEST_CANCELLED");
                response.setAnswer("已停止本次推荐请求");
                return response;
            }
            meterRegistry.counter("agent.fallback.total").increment();
            return fallback(request, response, context);
        } finally {
            timer.stop(Timer.builder("agent.request.latency").register(meterRegistry));
        }
    }

    /** Request coordinates are trusted client context; model output must not override them. */
    public static void applyRequestLocation(AgentModels.ChatRequest request, AgentModels.Intent intent) {
        boolean hasLatitude = request.getLatitude() != null;
        boolean hasLongitude = request.getLongitude() != null;
        if (hasLatitude != hasLongitude) {
            throw new IllegalArgumentException("latitude and longitude must be provided together");
        }
        if (hasLatitude) {
            intent.setLatitude(request.getLatitude());
            intent.setLongitude(request.getLongitude());
        } else {
            intent.setLatitude(null);
            intent.setLongitude(null);
        }
    }

    private List<Shop> callSearch(AgentModels.Intent intent, AgentContext context, List<String> calls) { return toolCall(shopSearchTool.name(), () -> shopSearchTool.execute(intent, context), calls); }
    private List<Shop> callNearby(AgentModels.Intent intent, AgentContext context, List<String> calls) { return toolCall(nearbyShopTool.name(), () -> nearbyShopTool.execute(intent, context), calls); }
    private List<Shop> toolCall(String name, java.util.function.Supplier<List<Shop>> action, List<String> calls) { calls.add(name); Timer.Sample timer = Timer.start(meterRegistry); try { return action.get(); } finally { timer.stop(Timer.builder("agent.tool.latency").tag("tool", name).register(meterRegistry)); } }
    private void enrich(List<AgentModels.ShopCard> cards, AgentModels.Intent intent, AgentContext context, List<String> calls) {
        for (AgentModels.ShopCard card : cards) {
            if (calls.size() >= MAX_TOOL_CALLS) break;
            calls.add(voucherTool.name()); card.setVouchers(voucherTool.execute(card.getShopId(), context));
        }
        if (Boolean.TRUE.equals(intent.getNeedVoucher())) cards.removeIf(card -> card.getVouchers().isEmpty());
        if (intent.getScene() != null && !cards.isEmpty() && calls.size() < MAX_TOOL_CALLS) {
            calls.add(contentTool.name());
            if (!contentTool.execute(cards.get(0).getShopId(), context).isEmpty()) cards.get(0).setReason(cards.get(0).getReason() + "；相关探店内容可供参考");
        }
    }
    private AgentModels.ChatResponse fallback(AgentModels.ChatRequest request, AgentModels.ChatResponse response, AgentContext context) {
        AgentModels.Intent intent = intentNormalizer.normalize(RuleBasedIntentParser.parseText(request.getMessage()));
        List<AgentModels.ShopCard> cards;
        try { cards = ranking.rank(shopSearchTool.execute(intent, context), intent); } catch (Exception ignored) { cards = List.of(); }
        response.setCards(cards); response.setFilters(filters(intent)); response.setFallback(true); response.setErrorCode("AGENT_FALLBACK");
        response.setAnswer("智能推荐暂时不可用，已为你切换到普通搜索，共找到 " + cards.size() + " 家商户。"); return response;
    }
    private void persist(String id, String text, AgentModels.Intent intent, String answer, List<String> calls) {
        AgentModels.Message user = new AgentModels.Message(); user.setRole("user"); user.setContent(text); user.setFilters(intent); user.setToolCalls(calls); memory.append(id, user);
        AgentModels.Message assistant = new AgentModels.Message(); assistant.setRole("assistant"); assistant.setContent(answer); assistant.setFilters(intent); assistant.setToolCalls(calls); memory.append(id, assistant);
    }
    private String answer(List<AgentModels.ShopCard> cards) { return cards.isEmpty() ? "暂时没有符合条件的商户，要不要放宽预算或扩大范围？" : "我为你找到 " + cards.size() + " 家比较合适的商户，价格、距离和营业状态均以本次业务查询结果为准。"; }
    private LinkedHashMap<String, Object> filters(AgentModels.Intent i) { LinkedHashMap<String,Object> m=new LinkedHashMap<>(); if(StrUtil.isNotBlank(i.getKeyword()))m.put("keyword",i.getKeyword()); if(StrUtil.isNotBlank(i.getLocation()))m.put("location",i.getLocation()); if(i.getBudgetMax()!=null)m.put("budgetMax",i.getBudgetMax()); if(i.getRadiusMeter()!=null)m.put("radiusMeter",i.getRadiusMeter()); if(i.getMinScore()!=null)m.put("minScore",i.getMinScore()); if(i.getOpenAt()!=null)m.put("openAt",i.getOpenAt()); if(i.getScene()!=null)m.put("scene",i.getScene()); if(Boolean.TRUE.equals(i.getNeedVoucher()))m.put("needVoucher",true); return m; }
    public List<AgentModels.Message> messages(String id){return currentUserId() == null || !memory.belongsTo(id, currentUserId()) ? List.of() : memory.read(id);}
    public void delete(String id){if(currentUserId() != null && memory.belongsTo(id, currentUserId())) memory.delete(id);}
    public List<String> conversations(){return currentUserId() == null ? List.of() : memory.listConversationIds(currentUserId());}
    private Long currentUserId(){return UserHolder.getUser() == null ? null : UserHolder.getUser().getId();}
}
