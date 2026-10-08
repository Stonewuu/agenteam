package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.mapper.auth.LoginAttemptMapper;
import com.stonewu.agenteam.support.IsolatedInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthLoginRateLimiterTest {

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private final String namespace = "agenteam:auth:test:" + UUID.randomUUID();

    @BeforeAll
    static void connectToIsolatedRedis() {
        var container = IsolatedInfrastructure.redis();
        connections = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void disconnect() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @Test
    void thirtyConcurrentAttemptsAreAllowedAcrossServiceInstances() throws Exception {
        var first = limiter();
        var second = limiter();
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Future<Boolean>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 50; index++) {
                var service = index % 2 == 0 ? first : second;
                tasks.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.checkSource("192.0.2.10");
                        return true;
                    } catch (LoginRateLimitException exception) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int allowed = 0;
            for (var task : tasks) {
                if (task.get()) {
                    allowed++;
                }
            }
            assertEquals(30, allowed);
        }
    }

    @Test
    void successClearsAccountFailuresButNotSourceLimits() {
        var service = limiter();
        for (int index = 0; index < 10; index++) {
            service.checkAccount("user:one");
            service.failed("user:one");
        }
        var failure = assertThrows(LoginRateLimitException.class, () -> service.checkAccount("user:one"));
        assertEquals(429, failure.getStatusCode().value());
        assertTrue(failure.retryAfterSeconds() > 890 && failure.retryAfterSeconds() <= 900);
        assertEquals(Long.toString(failure.retryAfterSeconds()), failure.getHeaders().getFirst("Retry-After"));
        for (int index = 0; index < 30; index++) {
            service.checkSource("192.0.2.11");
        }
        service.succeeded("user:one");
        assertDoesNotThrow(() -> service.checkAccount("user:one"));
        assertThrows(LoginRateLimitException.class, () -> service.checkSource("192.0.2.11"));
    }

    @Test
    void expiringOneOldAttemptDoesNotDiscardOtherRecentAttempts() {
        var service = limiter();
        for (int index = 0; index < 30; index++) {
            service.checkSource("192.0.2.12");
        }
        String key = redis.keys(namespace + ":source:*").iterator().next();
        String oldest = redis.opsForZSet().range(key, 0, 0).iterator().next();
        double currentScore = redis.opsForZSet().score(key, oldest);
        redis.opsForZSet().add(key, oldest, currentScore - 61_000);
        assertDoesNotThrow(() -> service.checkSource("192.0.2.12"));
        assertThrows(LoginRateLimitException.class, () -> service.checkSource("192.0.2.12"));
        assertEquals(30L, redis.opsForZSet().size(key));
    }

    @Test
    void unavailableCounterDoesNotAllowLoginToContinue() {
        var storage = mock(LoginAttemptMapper.class);
        when(storage.checkSource("192.0.2.13")).thenThrow(new DataAccessResourceFailureException("不可用"));
        var failure = assertThrows(ResponseStatusException.class,
            () -> new AuthLoginRateLimiter(storage).checkSource("192.0.2.13"));
        assertEquals(503, failure.getStatusCode().value());
    }

    private AuthLoginRateLimiter limiter() {
        return new AuthLoginRateLimiter(new LoginAttemptMapper(redis, namespace));
    }
}
