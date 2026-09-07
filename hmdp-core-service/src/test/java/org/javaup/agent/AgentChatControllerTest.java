package org.javaup.agent;

import org.javaup.agent.controller.AgentChatController;
import org.javaup.agent.model.AgentModels;
import org.javaup.agent.service.AgentOrchestrator;
import org.javaup.agent.service.AgentRateLimiter;
import org.javaup.agent.service.AgentRequestRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.core.MethodParameter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentChatControllerTest {
    @Test
    void conversationEndpointsDeclareTheirPathVariableName() throws NoSuchMethodException {
        Method messages = AgentChatController.class.getMethod("messages", String.class);
        Method delete = AgentChatController.class.getMethod("delete", String.class);

        assertEquals("id", new MethodParameter(messages, 0).getParameterAnnotation(PathVariable.class).value());
        assertEquals("id", new MethodParameter(delete, 0).getParameterAnnotation(PathVariable.class).value());
    }

    @Test
    void controllerIsGuardedByTheAgentFeatureFlag() {
        ConditionalOnProperty condition = AgentChatController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("agent", condition.prefix());
        assertEquals("enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
    }

    @Test
    void streamAndStopEndpointsAreExposed() throws NoSuchMethodException {
        assertTrue(AgentChatController.class.getMethod("stream", org.javaup.agent.model.AgentModels.ChatRequest.class, jakarta.servlet.http.HttpServletRequest.class)
                .isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class));
        assertTrue(AgentChatController.class.getMethod("stop", String.class)
                .isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class));
    }

    @Test
    void streamingDonePayloadMatchesNonStreamingResponse() throws Exception {
        AgentModels.ChatResponse response = sampleResponse();
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentRateLimiter rateLimiter = mock(AgentRateLimiter.class);
        AgentRequestRegistry requestRegistry = mock(AgentRequestRegistry.class);
        when(orchestrator.chat(any())).thenReturn(response);
        when(rateLimiter.tryAcquire(any(), any())).thenReturn(true);
        org.mockito.Mockito.when(requestRegistry.submit(any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });

        AgentChatController controller = new AgentChatController();
        ReflectionTestUtils.setField(controller, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(controller, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(controller, "requestRegistry", requestRegistry);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String requestBody = "{\"message\":\"附近3公里的火锅店\",\"clientRequestId\":\"sse-consistency\",\"latitude\":30.32,\"longitude\":120.15}";

        String json = mvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        MvcResult streaming = mvc.perform(post("/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(request().asyncStarted()).andReturn();
        String sse = mvc.perform(asyncDispatch(streaming))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode nonStreaming = mapper.readTree(json);
        JsonNode streamingDone = mapper.readTree(eventData(sse, "done"));
        assertEquals(nonStreaming, streamingDone);
        assertTrue(sse.contains("event:shop_card"));
    }

    private static AgentModels.ChatResponse sampleResponse() {
        AgentModels.ShopCard card = new AgentModels.ShopCard();
        card.setShopId(5L);
        card.setName("海底捞火锅");
        card.setDistanceMeter(1282D);
        AgentModels.ChatResponse response = new AgentModels.ChatResponse();
        response.setConversationId("c_test");
        response.setTraceId("trace-test");
        response.setAnswer("我为你找到 1 家商户。");
        response.setCards(java.util.List.of(card));
        response.setFilters(java.util.Map.of("keyword", "火锅", "radiusMeter", 3000));
        return response;
    }

    private static String eventData(String sse, String eventName) {
        for (String frame : sse.split("\\r?\\n\\r?\\n")) {
            if (!frame.contains("event:" + eventName)) continue;
            return frame.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5))
                    .reduce("", String::concat);
        }
        throw new AssertionError("Missing SSE event: " + eventName + " in " + sse);
    }
}
