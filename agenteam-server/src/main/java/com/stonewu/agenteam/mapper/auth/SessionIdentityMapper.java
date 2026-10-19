package com.stonewu.agenteam.mapper.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 会话切换以 Redis 原子比较为准，旧请求不能覆盖新身份。 */
@Repository
public class SessionIdentityMapper {
    private static final DefaultRedisScript<Long> ADVANCE = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[1]) or ARGV[1]
        if current ~= ARGV[2] then return 0 end
        redis.call('SET', KEYS[1], ARGV[3], 'EX', 604800)
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;

    public SessionIdentityMapper(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean matches(String root, String revision) {
        String current = redis.opsForValue().get(key(root));
        return revision.equals(current == null ? root : current);
    }

    public boolean advance(String root, String previous, String next) {
        return Long.valueOf(1).equals(redis.execute(ADVANCE, List.of(key(root)), root, previous, next));
    }

    private String key(String root) {
        return "agenteam:auth:identity:" + root;
    }
}
