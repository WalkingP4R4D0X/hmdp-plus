package org.javaup.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentModels;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Tracks in-flight Agent requests and short-lived idempotency results per application instance. */
@Component
public class AgentRequestRegistry {
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "agent-request");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
    private final Map<String, AgentModels.ChatResponse> completed = new ConcurrentHashMap<>();

    public AgentModels.ChatResponse completed(String requestId) {
        if (requestId == null) return null;
        AgentModels.ChatResponse local = completed.get(requestId);
        if (local != null || redis == null || objectMapper == null) return local;
        try {
            String value = redis.opsForValue().get(resultKey(requestId));
            AgentModels.ChatResponse result = value == null ? null : objectMapper.readValue(value, AgentModels.ChatResponse.class);
            if (result != null) completed.put(requestId, result);
            return result;
        } catch (Exception ignored) { return null; }
    }

    public boolean isActive(String requestId) {
        return requestId != null && active.containsKey(requestId);
    }

    public boolean submit(String requestId, Runnable task) {
        if (requestId == null || requestId.isBlank() || completed.containsKey(requestId) || active.containsKey(requestId)) return false;
        FutureTask<?> future = new FutureTask<>(() -> {
            try {
                task.run();
            } finally {
                active.remove(requestId);
            }
        }, null);
        if (active.putIfAbsent(requestId, future) != null) return false;
        executor.execute(future);
        return true;
    }

    public void complete(String requestId, AgentModels.ChatResponse response) {
        if (requestId != null && response != null) {
            completed.put(requestId, response);
            if (redis != null && objectMapper != null) {
                try { redis.opsForValue().set(resultKey(requestId), objectMapper.writeValueAsString(response), RESULT_TTL); }
                catch (Exception ignored) { }
            }
        }
    }

    public boolean cancel(String requestId) {
        Future<?> future = requestId == null ? null : active.remove(requestId);
        return future != null && future.cancel(true);
    }

    private String resultKey(String requestId) { return "agent:request:result:" + requestId; }
}
