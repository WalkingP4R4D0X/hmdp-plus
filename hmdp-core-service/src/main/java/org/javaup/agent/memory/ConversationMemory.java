package org.javaup.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentModels;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationMemory {
    private static final Duration TTL = Duration.ofDays(7);
    @Resource private StringRedisTemplate redis;
    @Resource private ObjectMapper objectMapper;

    public String ensureId(String id) { return id == null || id.isBlank() ? "c_" + UUID.randomUUID() : id; }

    public List<AgentModels.Message> read(String id) {
        try {
            String value = redis.opsForValue().get(key(id));
            if (value == null) return new ArrayList<>();
            redis.expire(key(id), TTL);
            return objectMapper.readValue(value, new TypeReference<List<AgentModels.Message>>() {});
        } catch (Exception ignored) { return new ArrayList<>(); }
    }

    public void append(String id, AgentModels.Message message) {
        try {
            List<AgentModels.Message> messages = read(id);
            messages.add(message);
            if (messages.size() > 20) messages = new ArrayList<>(messages.subList(messages.size() - 20, messages.size()));
            redis.opsForValue().set(key(id), objectMapper.writeValueAsString(messages), TTL);
        } catch (Exception ignored) { }
    }

    public void delete(String id) { try { redis.delete(key(id)); } catch (Exception ignored) { } }
    private String key(String id) { return "agent:conversation:" + id; }
}
