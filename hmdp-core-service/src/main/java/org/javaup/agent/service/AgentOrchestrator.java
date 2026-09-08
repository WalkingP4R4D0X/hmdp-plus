package org.javaup.agent.service;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.agent.memory.ConversationMemory;
import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import org.javaup.agent.ranking.ShopRankingService;
import org.javaup.agent.tool.NearbySearchResult;
import org.javaup.agent.tool.NearbyShopTool;
import org.javaup.agent.tool.ShopContentTool;
import org.javaup.agent.tool.ShopSearchTool;
import org.javaup.agent.tool.VoucherTool;
import org.javaup.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Service
public class AgentOrchestrator {
    private static final int MAX_TOOL_CALLS = 5;
    @Resource private ConversationMemory memory;
    @Resource private IntentParser intentParser;
    @Resource private IntentNormalizer intentNormalizer;
    @Resource private ShopSearchTool shopSearchTool;
    @Resource private NearbyShopTool nearbyShopTool;
    @Resource private VoucherTool voucherTool;
    @Resource private ShopContentTool contentTool;
    @Resource private ShopRankingService ranking;
    @Resource private DeepSeekClient deepSeekClient;

    public AgentModels.ChatResponse chat(AgentModels.ChatRequest request) {
        Long userId = currentUserId();
        return chat(request, userId == null ? "guest:" + UUID.randomUUID() : userId.toString());
    }

    public AgentModels.ChatResponse chat(AgentModels.ChatRequest request, String owner) {
        return chat(request, owner, currentUserId());
    }

    public AgentModels.ChatResponse chat(AgentModels.ChatRequest request, String owner, Long userId) {
        return chatInternal(request, owner, userId, null, null);
    }

    public AgentModels.ChatResponse chatStream(AgentModels.ChatRequest request, String owner, Long userId,
                                               Consumer<AgentModels.ChatResponse> onPrepared,
                                               Consumer<String> onDelta) {
        return chatInternal(request, owner, userId, onPrepared, onDelta);
    }

    private AgentModels.ChatResponse chatInternal(AgentModels.ChatRequest request, String owner, Long userId,
                                                  Consumer<AgentModels.ChatResponse> onPrepared,
                                                  Consumer<String> onDelta) {
        long started = System.currentTimeMillis();
        AgentModels.ChatResponse response = new AgentModels.ChatResponse();
        response.setTraceId("trace-" + UUID.randomUUID());
        List<String> calls = new ArrayList<>();
        try {
            if (request.getMessage() == null || request.getMessage().isBlank() || request.getMessage().length() > 500
                    || !request.isLocationValid()) {
                return error(response, "AGENT_REQUEST_INVALID", "请输入1–500字的查店需求，并提供有效的完整定位。");
            }
            checkCancelled();
            ConversationMemory.State state = memory.open(request.getConversationId(), owner);
            response.setConversationId(state.id());
            AgentContext context = new AgentContext(userId, state.id());
            AgentModels.Intent intent;
            try {
                intent = intentNormalizer.normalize(intentParser.parse(request.getMessage(), state.messages()));
            } catch (RuntimeException e) {
                checkCancelled();
                logFailure(response, "parseIntent", e);
                intent = fallbackIntent(request, state);
            }
            checkCancelled();
            applyRequestLocation(request, intent);
            response.setFilters(filters(intent));
            if ("GREETING".equals(intent.getIntent())) {
                response.setAnswer("你好，我是黑马点评智能导购。告诉我想找的品类、区域、预算或距离，我来帮你推荐。");
            } else if ("INVALID_INPUT".equals(intent.getParseStatus())) {
                error(response, "AGENT_INPUT_UNCLEAR", "请补充具体的品类、区域、预算或距离，例如：拱墅区人均100元以内的日料。");
            } else if (!hasSearchConstraint(intent)) {
                if ("MODEL_FALLBACK".equals(intent.getParseStatus())) response.setFallback(true);
                error(response, "AGENT_REQUEST_INVALID", "请告诉我想找什么店，例如：人均100元以内的日料，或附近3公里的火锅店。");
            } else {
                boolean fallback = "MODEL_FALLBACK".equals(intent.getParseStatus());
                response.setFallback(fallback);
                query(intent, context, response, calls, fallback);
                if (response.getErrorCode() == null && !fallback && !response.getCards().isEmpty()) {
                    try {
                        String answer;
                        if (onDelta != null && deepSeekClient.isConfigured()) {
                            onPrepared.accept(response);
                            answer = deepSeekClient.explainStream(intent, response.getCards(), onDelta);
                        } else {
                            answer = deepSeekClient.explain(intent, response.getCards());
                        }
                        checkCancelled();
                        if (StrUtil.isNotBlank(answer)) response.setAnswer(answer);
                        else throw new IllegalStateException("Explanation unavailable");
                    } catch (RuntimeException e) {
                        checkCancelled();
                        logFailure(response, "explain", e);
                        // Preserve history and all explicit constraints in ordinary search.
                        intent = fallbackIntent(request, state);
                        applyRequestLocation(request, intent);
                        response.setFilters(filters(intent));
                        response.setFallback(true);
                        response.setErrorCode(null);
                        query(intent, context, response, calls, true);
                    }
                }
            }
            checkCancelled();
            if (response.isFallback()) {
                response.setAnswer("智能推荐暂时不可用，已切换到普通搜索。" + response.getAnswer());
                if (response.getErrorCode() == null) response.setErrorCode("AGENT_FALLBACK");
            }
            AgentModels.Message user = message("user", request.getMessage(), intent, calls);
            AgentModels.Message assistant = message("assistant", response.getAnswer(), intent, calls);
            response.setMemorySaved(memory.saveTurn(state, user, assistant));
            if (!response.isMemorySaved()) response.setAnswer(response.getAnswer() + " 本轮上下文未保存，请在下次查询时重新说明条件。");
            return response;
        } catch (ConversationMemory.AccessDenied e) {
            return error(response, "AGENT_UNAUTHORIZED", "会话不存在或无权访问，请开始新会话。");
        } catch (CancellationException e) {
            return error(response, "AGENT_REQUEST_CANCELLED", "已停止本次推荐请求。");
        } catch (RuntimeException e) {
            logFailure(response, "query", e);
            response.setCards(List.of());
            return error(response, "AGENT_TOOL_UNAVAILABLE", "商户查询暂时不可用，请稍后重试。");
        } finally {
            log.info("agent_request traceId={} tools={} latencyMs={} fallback={} errorCode={}",
                    response.getTraceId(), calls, System.currentTimeMillis() - started, response.isFallback(), response.getErrorCode());
        }
    }

    private AgentModels.Intent fallbackIntent(AgentModels.ChatRequest request, ConversationMemory.State state) {
        AgentModels.Intent intent = intentNormalizer.merge(RuleBasedIntentParser.parseText(request.getMessage()), state.messages(), request.getMessage());
        intent.setParseStatus("MODEL_FALLBACK");
        return intentNormalizer.normalize(intent);
    }

    private void query(AgentModels.Intent intent, AgentContext context, AgentModels.ChatResponse response,
                       List<String> calls, boolean fallback) {
        checkCancelled();
        if (fallback && intent.getRadiusMeter() != null) {
            // Ordinary database search has no measured distance evidence.
            intent.setRadiusMeter(null);
            intent.setLatitude(null);
            intent.setLongitude(null);
            response.setFilters(filters(intent));
            if (!hasSearchConstraint(intent)) {
                error(response, "AGENT_FALLBACK", "请补充商户品类或所在区域，我会使用普通搜索继续查找。");
                return;
            }
        }
        if (intent.getRadiusMeter() != null && intent.getLatitude() == null) {
            error(response, "AGENT_NO_LOCATION", "请先允许浏览器定位；也可以取消距离限制，按区域或关键词搜索。");
            return;
        }
        List<AgentModels.ShopCard> cards;
        if (intent.getRadiusMeter() != null && !fallback) {
            NearbySearchResult nearby = toolCall(nearbyShopTool.name(), () -> nearbyShopTool.executeDetailed(intent, context), calls);
            if (nearby.status() != NearbySearchResult.Status.SUCCESS) {
                applyNearbyStatus(response, nearby.status());
                return;
            }
            cards = ranking.rankCandidates(nearby.shops(), intent);
        } else {
            cards = ranking.rankCandidates(toolCall(shopSearchTool.name(), () -> shopSearchTool.execute(intent, context), calls), intent);
        }
        if (!cards.isEmpty()) {
            Map<Long, List<AgentModels.VoucherCard>> vouchers = toolCall(voucherTool.name(),
                    () -> voucherTool.executeBatch(cards.stream().map(AgentModels.ShopCard::getShopId).toList(), context), calls);
            for (AgentModels.ShopCard card : cards) card.setVouchers(vouchers.getOrDefault(card.getShopId(), List.of()));
            if (Boolean.TRUE.equals(intent.getNeedVoucher())) cards.removeIf(card -> card.getVouchers().isEmpty());
        }
        // All hard filters, including voucher availability, run before the final result cap.
        response.setCards(new ArrayList<>(cards.stream().limit(10).toList()));
        if (!fallback && intent.getScene() != null && !response.getCards().isEmpty() && calls.size() < MAX_TOOL_CALLS - 2) {
            AgentModels.ShopCard first = response.getCards().get(0);
            List<String> content = toolCall(contentTool.name(), () -> contentTool.execute(first.getShopId(), context), calls);
            if (!content.isEmpty()) first.setReason(first.getReason() + "；有相关探店内容可供参考");
        }
        response.setAnswer(response.getCards().isEmpty()
                ? "暂时没有符合条件的商户，请尝试放宽距离、预算、评分或营业时间。"
                : "共找到 " + response.getCards().size() + " 家符合筛选条件的商户，详细信息见下方卡片。");
    }

    private <T> T toolCall(String name, Supplier<T> action, List<String> calls) {
        checkCancelled();
        if (calls.size() >= MAX_TOOL_CALLS) throw new IllegalStateException("Tool call limit reached");
        calls.add(name);
        T result = action.get();
        checkCancelled();
        return result;
    }

    private void applyNearbyStatus(AgentModels.ChatResponse response, NearbySearchResult.Status status) {
        response.setCards(List.of());
        switch (status) {
            case NO_LOCATION -> error(response, "AGENT_NO_LOCATION", "请先允许定位，或取消距离限制后按区域搜索。");
            case INDEX_EMPTY -> error(response, "AGENT_GEO_INDEX_EMPTY", "附近商户索引正在准备中，请稍后重试。");
            case REDIS_UNAVAILABLE -> error(response, "AGENT_GEO_UNAVAILABLE", "附近检索暂时不可用，请稍后重试或取消距离限制。");
            case NO_MATCH -> error(response, "AGENT_NO_RESULT", "暂时没有符合条件的附近商户，请尝试扩大范围或放宽条件。");
            default -> throw new IllegalArgumentException("Unexpected nearby status");
        }
    }

    public static void applyRequestLocation(AgentModels.ChatRequest request, AgentModels.Intent intent) {
        if (!request.isLocationValid()) throw new IllegalArgumentException("Invalid coordinate pair");
        intent.setLatitude(request.getLatitude());
        intent.setLongitude(request.getLongitude());
    }

    public static boolean hasSearchConstraint(AgentModels.Intent intent) {
        return StrUtil.isNotBlank(intent.getKeyword()) || StrUtil.isNotBlank(intent.getLocation())
                || intent.getRadiusMeter() != null || intent.getBudgetMax() != null || intent.getMinScore() != null
                || StrUtil.isNotBlank(intent.getOpenAt()) || StrUtil.isNotBlank(intent.getScene())
                || Boolean.TRUE.equals(intent.getNeedVoucher());
    }

    private LinkedHashMap<String, Object> filters(AgentModels.Intent i) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (i.getKeyword() != null) result.put("keyword", i.getKeyword());
        if (i.getLocation() != null) result.put("location", i.getLocation());
        if (i.getBudgetMax() != null) result.put("budgetMax", i.getBudgetMax());
        if (i.getRadiusMeter() != null) result.put("radiusMeter", i.getRadiusMeter());
        if (i.getMinScore() != null) result.put("minScore", i.getMinScore());
        if (i.getOpenAt() != null) result.put("openAt", i.getOpenAt());
        if (i.getScene() != null) result.put("scene", i.getScene());
        if (i.getNeedVoucher() != null) result.put("needVoucher", i.getNeedVoucher());
        return result;
    }

    private static AgentModels.Message message(String role, String text, AgentModels.Intent intent, List<String> calls) {
        AgentModels.Message message = new AgentModels.Message();
        message.setRole(role);
        message.setContent(text);
        message.setFilters(intent);
        message.setToolCalls(List.copyOf(calls));
        return message;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static AgentModels.ChatResponse error(AgentModels.ChatResponse response, String code, String answer) {
        response.setErrorCode(code);
        response.setAnswer(answer);
        return response;
    }

    private static void logFailure(AgentModels.ChatResponse response, String tool, Exception e) {
        log.warn("agent_failure traceId={} tool={} exceptionType={}", response.getTraceId(), tool, e.getClass().getSimpleName());
    }

    public List<AgentModels.Message> messages(String id, String owner) { return memory.read(id, owner); }
    public void delete(String id, String owner) { memory.delete(id, owner); }
    private static Long currentUserId() { return UserHolder.getUser() == null ? null : UserHolder.getUser().getId(); }
}
