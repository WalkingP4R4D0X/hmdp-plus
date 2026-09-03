package org.javaup.agent.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.agent.model.AgentModels;
import org.javaup.agent.service.AgentOrchestrator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;

@RestController
@RequestMapping("/agent")
public class AgentChatController {
    @Resource private AgentOrchestrator orchestrator;
    @PostMapping("/chat") public AgentModels.ChatResponse chat(@Valid @RequestBody AgentModels.ChatRequest request){ return orchestrator.chat(request); }
    @PostMapping(value="/chat/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AgentModels.ChatRequest request){ SseEmitter emitter=new SseEmitter(10000L); try { AgentModels.ChatResponse r=orchestrator.chat(request); send(emitter,"status",r.getTraceId(),1); send(emitter,"filter_update",r.getFilters(),2); int seq=3; for(AgentModels.ShopCard c:r.getCards())send(emitter,"shop_card",c,seq++); send(emitter,"text_delta",r.getAnswer(),seq++); if(r.isFallback())send(emitter,"fallback",r.getErrorCode(),seq++); send(emitter,"done",r,seq); emitter.complete(); } catch(Exception e){ try{send(emitter,"error","AGENT_TOOL_TIMEOUT",1);send(emitter,"done",null,2);}catch(Exception ignored){} emitter.complete(); } return emitter; }
    @GetMapping("/conversations") public Object conversations(){return orchestrator.conversations();}
    @GetMapping("/conversations/{id}/messages") public Object messages(@PathVariable String id){return orchestrator.messages(id);}
    @DeleteMapping("/conversations/{id}") public void delete(@PathVariable String id){orchestrator.delete(id);}
    private void send(SseEmitter e,String event,Object data,int seq)throws IOException{e.send(SseEmitter.event().name(event).id(String.valueOf(seq)).data(data));}
}
