package com.stonewu.agenteam.mapper.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 多个后端实例共享登录计数；脚本一次完成计数与过期设置。
 */
@Component
public class LoginAttemptMapper {

    private static final DefaultRedisScript<Long> SOURCE = new DefaultRedisScript<>("""
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 60000)
        if redis.call('ZCARD', KEYS[1]) >= 30 then
            local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            return math.max(math.ceil((oldest[2] + 60000 - now) / 1000), 1)
        end
        redis.call('ZADD', KEYS[1], now, ARGV[1])
        redis.call('PEXPIRE', KEYS[1], 60000)
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> ACCOUNT = new DefaultRedisScript<>("""
        local remaining = redis.call('PTTL', KEYS[1])
        if remaining > 0 then return math.ceil(remaining / 1000) end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> FAILURE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[2]) == 1 then return 10 end
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 900000)
        redis.call('ZADD', KEYS[1], now, ARGV[1])
        redis.call('PEXPIRE', KEYS[1], 900000)
        local count = redis.call('ZCARD', KEYS[1])
        if count >= 10 then redis.call('SET', KEYS[2], 'locked', 'EX', 900, 'NX') end
        return count
        """, Long.class);
    private static final DefaultRedisScript<Long> SUCCESS = new DefaultRedisScript<>("""
        return redis.call('DEL', KEYS[1], KEYS[2])
        """, Long.class);
    private final StringRedisTemplate redis;
    private final String namespace;

    public LoginAttemptMapper(StringRedisTemplate redis,
                              @Value("${agenteam.auth.rate-limit-namespace:agenteam:auth}") String namespace) {
        this.redis = redis;
        this.namespace = namespace;
    }

    public long checkSource(String source) {
        return execute(SOURCE, List.of(namespace + ":source:" + hash(source)), UUID.randomUUID().toString());
    }

    public long checkAccount(String account) {
        return execute(ACCOUNT, List.of(accountKey(account) + ":lock"));
    }

    public long failed(String account) {
        String key = accountKey(account);
        return execute(FAILURE, List.of(key + ":failures", key + ":lock"), UUID.randomUUID().toString());
    }

    public long succeeded(String account) {
        String key = accountKey(account);
        return execute(SUCCESS, List.of(key + ":failures", key + ":lock"));
    }

    private long execute(DefaultRedisScript<Long> script, List<String> keys, Object... arguments) {
        Long result = redis.execute(script, keys, arguments);
        if (result == null) {
            throw new DataAccessResourceFailureException("登录次数检查未取得结果");
        }
        return result;
    }

    private String accountKey(String account) {
        // 同一账号的相关键使用相同分组，支持 Redis 集群中的原子脚本。
        return namespace + ":account:{" + hash(account) + "}";
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境缺少必要的摘要算法", exception);
        }
    }
}
