package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 所有实例共享应用和接收人额度，任意一项额度不足时都不扣减其他额度。 */
@Service
public class ChannelRateLimiter {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>();

    static {
        ACQUIRE.setLocation(new ClassPathResource("redis/channel-rate-limit.lua"));
        ACQUIRE.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public ChannelRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Duration acquire(IntegrationApplication app, String recipient, List<ChannelRateLimit> limits) {
        if (limits.isEmpty()) {
            return Duration.ZERO;
        }
        var keys = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        arguments.add(UUID.randomUUID().toString());
        String prefix = "agenteam:channel:rate:{" + app.enterpriseId() + ":" + app.id() + "}:";
        String member = digest(recipient);
        for (var limit : limits) {
            keys.add(prefix + limit.name() + (limit.perRecipient() ? ":" + member : ""));
            arguments.add(limit.calendarDay() ? "day" : "rolling");
            arguments.add(Integer.toString(limit.windowSeconds()));
            arguments.add(Integer.toString(limit.maximum()));
            arguments.add(Integer.toString(limit.dayOffsetSeconds()));
        }
        Long wait = redis.execute(ACQUIRE, keys, arguments.toArray());
        if (wait == null || wait < 0) {
            throw new IllegalStateException("通知发送额度无法确认");
        }
        return Duration.ofMillis(wait);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("无法计算接收人额度编号", failure);
        }
    }
}
