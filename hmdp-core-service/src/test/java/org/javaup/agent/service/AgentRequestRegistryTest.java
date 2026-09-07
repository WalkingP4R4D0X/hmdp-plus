package org.javaup.agent.service;

import org.javaup.agent.model.AgentModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRequestRegistryTest {
    private final AgentRequestRegistry registry = new AgentRequestRegistry(
            1, 2, 16, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @AfterEach
    void close() {
        registry.close();
    }

    @Test
    void sameOwnerAndPayloadShareOneRequestButDifferentOwnersDoNot() throws Exception {
        AgentModels.ChatRequest input = request("r1", "找火锅");
        CountDownLatch release = new CountDownLatch(1);
        AgentRequestRegistry.Request first = registry.submit("user:1", input, () -> {
            await(release);
            return response("c1");
        });

        AgentRequestRegistry.Request duplicate = registry.submit("user:1", input, () -> response("wrong"));
        AgentRequestRegistry.Request otherOwner = registry.submit("user:2", input, () -> response("c2"));

        assertSame(first, duplicate);
        assertNotSame(first, otherOwner);
        release.countDown();
        assertEquals("c1", first.await(1, TimeUnit.SECONDS).getConversationId());
        assertEquals("c2", otherOwner.await(1, TimeUnit.SECONDS).getConversationId());
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsRejected() {
        AgentModels.ChatRequest first = request("r1", "找火锅");
        registry.submit("user:1", first, () -> response("c1"));

        AgentModels.ChatRequest changed = request("r1", "找日料");
        assertThrows(AgentRequestRegistry.PayloadConflict.class,
                () -> registry.submit("user:1", changed, () -> response("c2")));
    }

    private static AgentModels.ChatRequest request(String id, String message) {
        AgentModels.ChatRequest request = new AgentModels.ChatRequest();
        request.setClientRequestId(id);
        request.setMessage(message);
        return request;
    }

    private static AgentModels.ChatResponse response(String conversationId) {
        AgentModels.ChatResponse response = new AgentModels.ChatResponse();
        response.setConversationId(conversationId);
        return response;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) throw new AssertionError("request was not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
