package org.javaup;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.execute.RateLimitHandler;
import org.javaup.ratelimit.extension.RateLimitScene;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Slf4j
@SpringBootTest(classes = RedisRateLimitTestApplication.class)
class RedisRateLimitHandlerTest {
    
    @Resource
    private RateLimitHandler rateLimitHandler;

    @Test
    void test1() throws InterruptedException {
        assertDoesNotThrow(() -> rateLimitHandler.execute(1L, 1987041610793484289L, RateLimitScene.SECKILL_ORDER));
    }
   
}
