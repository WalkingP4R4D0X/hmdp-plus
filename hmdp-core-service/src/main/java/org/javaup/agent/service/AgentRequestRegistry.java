package org.javaup.agent.service;

import jakarta.annotation.PreDestroy;
import org.javaup.agent.model.AgentModels;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Single-instance, owner-scoped idempotency shared by JSON and SSE requests. */
@Component
public class AgentRequestRegistry {
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);
    private final ThreadPoolExecutor executor;
    private final Clock clock;
    private final int capacity;
    private final Map<Key, Request> requests = new HashMap<>();

    public AgentRequestRegistry() { this(4, 32, 1024, Clock.systemUTC()); }

    // Package-private configuration makes expiry and saturation tests deterministic.
    AgentRequestRegistry(int workers, int queueCapacity, int capacity, Clock clock) {
        this.clock = clock;
        this.capacity = capacity;
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "agent-request");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Transport flags are deliberately excluded: switching JSON/SSE is the same request. */
    private record Payload(String conversationId, String message, Double latitude, Double longitude) {
        static Payload of(AgentModels.ChatRequest request) {
            return new Payload(request.getConversationId(), request.getMessage(), request.getLatitude(), request.getLongitude());
        }
    }
    private record Key(String owner, String requestId) {}
    public static class PayloadConflict extends RuntimeException {}

    public synchronized Request submit(String owner, AgentModels.ChatRequest input,
                                       Supplier<AgentModels.ChatResponse> action) {
        Objects.requireNonNull(owner, "owner");
        long now = clock.millis();
        requests.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        // Missing clientRequestId opts out of deduplication, but still uses bounded execution.
        String id = input.getClientRequestId() == null ? UUID.randomUUID().toString() : input.getClientRequestId();
        Key key = new Key(owner, id);
        Payload payload = Payload.of(input);
        Request existing = requests.get(key);
        if (existing != null) {
            if (!existing.payload.equals(payload)) throw new PayloadConflict();
            return existing;
        }
        // Never evict a live or unexpired key: eviction would allow a duplicate save.
        if (requests.size() >= capacity) throw new RejectedExecutionException("Agent request capacity reached");
        Request request = new Request(payload);
        FutureTask<Void> task = new FutureTask<>(() -> {
            synchronized (request) {
                if (request.cancelled) return null;
                request.started = true;
            }
            try {
                AgentModels.ChatResponse response = Objects.requireNonNull(action.get(), "response");
                if (Thread.currentThread().isInterrupted()) request.cancel();
                // Cancellation completes/overwrites the future exceptionally; a late result cannot cache itself.
                request.result.complete(response);
            } catch (Throwable failure) {
                request.result.completeExceptionally(failure);
            } finally {
                request.expiresAt = clock.millis() + RESULT_TTL.toMillis();
            }
            return null;
        });
        request.task = task;
        requests.put(key, request);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException failure) {
            requests.remove(key, request);
            throw failure;
        }
        return request;
    }

    public boolean cancel(String owner, String requestId) {
        Request request;
        synchronized (this) { request = requests.get(new Key(owner, requestId)); }
        return request != null && request.cancel();
    }

    @PreDestroy
    public void close() {
        Request[] pending;
        synchronized (this) { pending = requests.values().toArray(Request[]::new); }
        for (Request request : pending) request.cancel();
        executor.shutdownNow();
    }

    public final class Request {
        private final Payload payload;
        private final CompletableFuture<AgentModels.ChatResponse> result = new CompletableFuture<>();
        private volatile long expiresAt = Long.MAX_VALUE;
        private FutureTask<Void> task;
        private boolean started;
        private boolean cancelled;

        private Request(Payload payload) { this.payload = payload; }

        public AgentModels.ChatResponse await(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }

        public void onResult(BiConsumer<AgentModels.ChatResponse, Throwable> listener) {
            result.whenComplete(listener);
        }

        public synchronized boolean isCancelled() { return cancelled; }

        /** Cancellation and each send share a lock, so no event starts after cancel returns. */
        public synchronized void ifNotCancelled(CheckedAction action) throws java.io.IOException {
            if (!cancelled) action.run();
        }

        public synchronized boolean cancel() {
            if (cancelled) return false;
            cancelled = true;
            task.cancel(true);
            executor.remove(task);
            // Keep a tombstone until the running supplier exits, even if it ignores interruption.
            if (!started) expiresAt = clock.millis() + RESULT_TTL.toMillis();
            result.obtrudeException(new CancellationException("Agent request cancelled"));
            return true;
        }
    }

    @FunctionalInterface
    public interface CheckedAction { void run() throws java.io.IOException; }
}
