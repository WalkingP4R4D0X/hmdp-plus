package org.javaup.agent.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.agent.model.AgentModels;
import org.javaup.agent.service.AgentOrchestrator;
import org.javaup.agent.service.AgentRateLimiter;
import org.javaup.agent.service.AgentRequestRegistry;
import org.javaup.utils.UserHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/agent")
@ConditionalOnProperty(prefix = "agent", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AgentChatController {
    @Resource private AgentOrchestrator orchestrator;
    @Resource private AgentRateLimiter rateLimiter;
    @Resource private AgentRequestRegistry requestRegistry;
    @PostMapping("/chat") public AgentModels.ChatResponse chat(@Valid @RequestBody AgentModels.ChatRequest request, HttpServletRequest httpRequest){
        if (!allow(request, httpRequest)) { AgentModels.ChatResponse response = new AgentModels.ChatResponse(); response.setErrorCode("AGENT_RATE_LIMITED"); response.setAnswer("请求过于频繁，请稍后再试"); return response; }
        String owner = owner(httpRequest);
        Long requestUserId = userId();
        try {
            return requestRegistry.submit(owner, request,
                    () -> orchestrator.chat(request, owner, requestUserId)).await(10, TimeUnit.SECONDS);
        } catch (AgentRequestRegistry.PayloadConflict e) {
            return error("AGENT_REQUEST_INVALID", "clientRequestId 已用于其他请求，请重新提交。");
        } catch (CancellationException e) {
            return error("AGENT_REQUEST_CANCELLED", "已停止本次推荐请求。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("AGENT_REQUEST_CANCELLED", "已停止本次推荐请求。");
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            return error("AGENT_TOOL_TIMEOUT", "查询超时，请稍后重试。");
        }
    }
    @PostMapping(value="/chat/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AgentModels.ChatRequest request, HttpServletRequest httpRequest){
        SseEmitter emitter = new SseEmitter(10000L);
        String owner = owner(httpRequest);
        Long requestUserId = userId();
        try {
            if (!allow(request, httpRequest)) { send(emitter, "error", "AGENT_RATE_LIMITED", 1); send(emitter, "done", null, 2); emitter.complete(); return emitter; }
            AgentRequestRegistry.Request pending = requestRegistry.submit(owner, request,
                    () -> orchestrator.chat(request, owner, requestUserId));
            pending.onResult((response, failure) -> {
                try {
                    pending.ifNotCancelled(() -> {
                        if (failure != null) {
                            send(emitter, "error", "AGENT_TOOL_TIMEOUT", 1);
                            send(emitter, "done", null, 2);
                        } else {
                            writeResponse(emitter, response);
                        }
                    });
                } catch (IOException ignored) {
                    emitter.completeWithError(ignored);
                }
            });
        } catch (AgentRequestRegistry.PayloadConflict e) {
            completeError(emitter, "AGENT_REQUEST_INVALID");
        } catch (RejectedExecutionException e) {
            completeError(emitter, "AGENT_TOOL_TIMEOUT");
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @PostMapping("/chat/stop")
    public void stop(@RequestParam String clientRequestId, HttpServletRequest request) { requestRegistry.cancel(owner(request), clientRequestId); }
    @GetMapping("/conversations/{id}/messages") public Object messages(@PathVariable("id") String id, HttpServletRequest request){return orchestrator.messages(id, owner(request));}
    @DeleteMapping("/conversations/{id}") public void delete(@PathVariable("id") String id, HttpServletRequest request){orchestrator.delete(id, owner(request));}
    private void send(SseEmitter e,String event,Object data,int seq)throws IOException{e.send(SseEmitter.event().name(event).id(String.valueOf(seq)).data(data));}
    private void completeError(SseEmitter emitter, String code) {
        try {
            send(emitter, "error", code, 1);
            send(emitter, "done", null, 2);
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
    private void writeResponse(SseEmitter emitter, AgentModels.ChatResponse response) throws IOException {
        send(emitter,"status",response.getTraceId(),1); send(emitter,"filter_update",response.getFilters(),2);
        int seq = 3; for (AgentModels.ShopCard card : response.getCards()) send(emitter,"shop_card",card,seq++);
        String answer = response.getAnswer() == null ? "" : response.getAnswer();
        for (int from = 0; from < answer.length(); from += 24) send(emitter,"text_delta",answer.substring(from, Math.min(answer.length(), from + 24)),seq++);
        if (response.isFallback()) send(emitter,"fallback",response.getErrorCode(),seq++);
        else if (response.getErrorCode() != null) send(emitter,"error",response.getErrorCode(),seq++);
        send(emitter,"done",response,seq); emitter.complete();
    }
    private boolean allow(AgentModels.ChatRequest request, HttpServletRequest httpRequest) {
        boolean allowed = rateLimiter.tryAcquire("ip", httpRequest.getRemoteAddr());
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        allowed &= rateLimiter.tryAcquire("user", userId == null ? null : String.valueOf(userId));
        allowed &= rateLimiter.tryAcquire("conversation", request.getConversationId());
        return allowed;
    }

    private String owner(HttpServletRequest request) {
        Long userId = userId();
        // Keep the Redis user index aligned with agent:conversation:user:{userId}.
        if (userId != null) return String.valueOf(userId);
        return "guest:" + request.getSession(true).getId();
    }

    private static Long userId() {
        return UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
    }

    private static AgentModels.ChatResponse error(String code, String answer) {
        AgentModels.ChatResponse response = new AgentModels.ChatResponse();
        response.setErrorCode(code);
        response.setAnswer(answer);
        return response;
    }
}
