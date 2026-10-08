package com.stonewu.agenteam.mapper.auth;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 找回密码按来源限制请求，账号是否匹配不影响此计数。
 */
@Repository
public class PasswordResetSourceMapper {
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 3600000)
        if redis.call('ZCARD', KEYS[1]) >= 20 then
          local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
          return math.max(math.ceil((oldest[2] + 3600000 - now) / 1000), 1)
        end
        redis.call('ZADD', KEYS[1], now, ARGV[1])
        redis.call('PEXPIRE', KEYS[1], 3600000)
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;

    public PasswordResetSourceMapper(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long check(String source) {
        try {
            String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            Long result = redis.execute(CHECK, List.of("agenteam:auth:reset-source:" + hash),
                UUID.randomUUID().toString());
            if (result == null) {
                throw new DataAccessResourceFailureException("找回密码次数检查未取得结果");
            }
            return result;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少必要的摘要算法", exception);
        }
    }
}
