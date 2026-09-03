package org.javaup;

import org.javaup.config.RateLimitAutoConfiguration;
import org.javaup.config.RedisFrameWorkAutoConfig;
import org.javaup.redis.config.RedisCacheAutoConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Minimal application context for testing the rate-limit component in isolation. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({RedisFrameWorkAutoConfig.class, RedisCacheAutoConfig.class, RateLimitAutoConfiguration.class})
public class RedisRateLimitTestApplication {
}
