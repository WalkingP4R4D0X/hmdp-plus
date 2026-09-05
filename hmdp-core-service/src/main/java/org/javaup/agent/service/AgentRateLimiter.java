package org.javaup.agent.service;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis-backed fixed window limiter dedicated to the read-only Agent endpoints. */
@Component
public class AgentRateLimiter {
    @Resource
    private StringRedisTemplate redis;

    @Value("${agent.limits.rate-limit-window:60s}")
    private Duration window;
    @Value("${agent.limits.rate-limit-per-window:20}")
    private long limit;

    public boolean tryAcquire(String dimension, String value) {
        if (value == null || value.isBlank()) return true;
        try {
            String key = "agent:rate:" + dimension + ":" + value;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, window);
            return count == null || count <= limit;
        } catch (RuntimeException ignored) {
            // A limiter outage must not take down the read-only Agent or core APIs.
            return true;
        }
    }
}
