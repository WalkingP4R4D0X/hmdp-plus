package org.javaup.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentModels;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Owner and messages share a key and TTL; guest owners come from the server session. */
@Service
public class ConversationMemory {
    static final Duration TTL = Duration.ofDays(7);
    @Resource private StringRedisTemplate redis;
    @Resource private ObjectMapper objectMapper;

    public record Conversation(String owner, List<AgentModels.Message> messages) {}
    public record State(String id, String owner, List<AgentModels.Message> messages, String snapshot, boolean available) {}
    public static class AccessDenied extends RuntimeException {}
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[1])
            if (old or '') ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            redis.call('SADD', KEYS[2], ARGV[4])
            redis.call('EXPIRE', KEYS[2], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> DELETE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    public State open(String id, String owner) {
        if (id == null || id.isBlank()) return new State(newId(), owner, List.of(), null, true);
        try {
            String snapshot = redis.opsForValue().get(key(id));
            if (snapshot == null) throw new AccessDenied();
            Conversation conversation = objectMapper.readValue(snapshot, Conversation.class);
            if (!owner.equals(conversation.owner())) throw new AccessDenied();
            return new State(id, owner, conversation.messages(), snapshot, true);
        } catch (AccessDenied e) {
            throw e;
        } catch (Exception e) {
            // Never bypass ownership or reuse a supplied ID when Redis cannot verify it.
            return new State(newId(), owner, List.of(), null, false);
        }
    }

    public boolean saveTurn(State state, AgentModels.Message user, AgentModels.Message assistant) {
        if (!state.available() || Thread.currentThread().isInterrupted()) return false;
        try {
            List<AgentModels.Message> messages = new ArrayList<>(state.messages());
            messages.add(user);
            messages.add(assistant);
            if (messages.size() > 20) messages = new ArrayList<>(messages.subList(messages.size() - 20, messages.size()));
            String json = objectMapper.writeValueAsString(new Conversation(state.owner(), messages));
            return Long.valueOf(1).equals(redis.execute(SAVE, List.of(key(state.id()), userKey(state.owner())),
                    state.snapshot() == null ? "" : state.snapshot(), json, String.valueOf(TTL.toSeconds()), state.id()));
        } catch (Exception e) {
            return false;
        }
    }

    public List<AgentModels.Message> read(String id, String owner) {
        State state = open(id, owner);
        return state.available() ? state.messages() : List.of();
    }

    public void delete(String id, String owner) {
        State state = open(id, owner);
        if (!state.available()) throw new IllegalStateException("Conversation store unavailable");
        redis.execute(DELETE, List.of(key(id), userKey(owner)), state.snapshot(), id);
    }

    public List<String> listConversationIds(String owner) {
        try {
            Set<String> ids = redis.opsForSet().members(userKey(owner));
            if (ids == null) return List.of();
            List<String> result = new ArrayList<>();
            for (String id : ids) {
                if (Boolean.TRUE.equals(redis.hasKey(key(id)))) result.add(id);
                else redis.opsForSet().remove(userKey(owner), id);
            }
            return result.stream().sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String newId() { return "c_" + UUID.randomUUID(); }
    private static String key(String id) { return "agent:conversation:" + id; }
    private static String userKey(String owner) { return "agent:conversation:user:" + owner; }
}