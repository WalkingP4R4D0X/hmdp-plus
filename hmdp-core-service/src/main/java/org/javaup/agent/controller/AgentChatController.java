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

@RestController
@RequestMapping("/agent")
@ConditionalOnProperty(prefix = "agent", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AgentChatController {
    @Resource private AgentOrchestrator orchestrator;
    @Resource private AgentRateLimiter rateLimiter;
    @Resource private AgentRequestRegistry requestRegistry;
    @PostMapping("/chat") public AgentModels.ChatResponse chat(@Valid @RequestBody AgentModels.ChatRequest request, HttpServletRequest httpRequest){
        if (!allow(request, httpRequest)) { AgentModels.ChatResponse response = new AgentModels.ChatResponse(); response.setErrorCode("AGENT_RATE_LIMITED"); response.setAnswer("请求过于频繁，请稍后再试"); return response; }
        return orchestrator.chat(request);
    }
    @PostMapping(value="/chat/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AgentModels.ChatRequest request, HttpServletRequest httpRequest){
        SseEmitter emitter = new SseEmitter(10000L);
        String requestId = request.getClientRequestId();
        try {
            if (!allow(request, httpRequest)) { send(emitter, "error", "AGENT_RATE_LIMITED", 1); send(emitter, "done", null, 2); emitter.complete(); return emitter; }
            AgentModels.ChatResponse cached = requestRegistry.completed(requestId);
            if (cached != null) { writeResponse(emitter, cached); return emitter; }
            if (requestId == null || requestId.isBlank()) {
                writeResponse(emitter, orchestrator.chat(request));
                return emitter;
            }
            boolean accepted = requestRegistry.submit(requestId, () -> {
                try {
                    AgentModels.ChatResponse response = orchestrator.chat(request);
                    requestRegistry.complete(requestId, response);
                    writeResponse(emitter, response);
                } catch (Exception e) {
                    try { send(emitter, "error", "AGENT_TOOL_TIMEOUT", 1); send(emitter, "done", null, 2); } catch (IOException ignored) { }
                    emitter.completeWithError(e);
                }
            });
            if (!accepted) {
                send(emitter, "error", requestRegistry.isActive(requestId) ? "AGENT_REQUEST_IN_PROGRESS" : "AGENT_REQUEST_INVALID", 1);
                emitter.complete();
            }
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @PostMapping("/chat/stop")
    public void stop(@RequestParam String clientRequestId) { requestRegistry.cancel(clientRequestId); }
    @GetMapping("/conversations") public Object conversations(){return orchestrator.conversations();}
    @GetMapping("/conversations/{id}/messages") public Object messages(@PathVariable("id") String id){return orchestrator.messages(id);}
    @DeleteMapping("/conversations/{id}") public void delete(@PathVariable("id") String id){orchestrator.delete(id);}
    private void send(SseEmitter e,String event,Object data,int seq)throws IOException{e.send(SseEmitter.event().name(event).id(String.valueOf(seq)).data(data));}
    private void writeResponse(SseEmitter emitter, AgentModels.ChatResponse response) throws IOException {
        send(emitter,"status",response.getTraceId(),1); send(emitter,"filter_update",response.getFilters(),2);
        int seq = 3; for (AgentModels.ShopCard card : response.getCards()) send(emitter,"shop_card",card,seq++);
        String answer = response.getAnswer() == null ? "" : response.getAnswer();
        for (int from = 0; from < answer.length(); from += 24) send(emitter,"text_delta",answer.substring(from, Math.min(answer.length(), from + 24)),seq++);
        if (response.isFallback()) send(emitter,"fallback",response.getErrorCode(),seq++);
        send(emitter,"done",response,seq); emitter.complete();
    }
    private boolean allow(AgentModels.ChatRequest request, HttpServletRequest httpRequest) {
        boolean allowed = rateLimiter.tryAcquire("ip", httpRequest.getRemoteAddr());
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        allowed &= rateLimiter.tryAcquire("user", userId == null ? null : String.valueOf(userId));
        allowed &= rateLimiter.tryAcquire("conversation", request.getConversationId());
        return allowed;
    }
}
