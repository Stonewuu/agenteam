package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 应用令牌按凭据版本加密缓存；刷新锁跨进程共享，旧请求不能删除其他进程刚刷新的令牌。
 */
@Service
public class IntegrationTokenService {
    private static final DefaultRedisScript<Long> DELETE_MATCHING = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    private final IntegrationProviderRegistry providers;
    private final IntegrationSecretService secrets;
    private final PayloadEncryption encryption;
    private final ObjectMapper json;
    private final Clock clock;

    public IntegrationTokenService(StringRedisTemplate redis, IntegrationProviderRegistry providers,
                                   IntegrationSecretService secrets, PayloadEncryption encryption, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.providers = providers;
        this.secrets = secrets;
        this.encryption = encryption;
        this.json = json;
        this.clock = clock;
    }

    public ChannelAccessToken get(IntegrationApplication application) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("应用令牌刷新不能在数据库事务中执行");
        }
        String key = key(application);
        var cached = cached(key);
        if (cached != null) {
            return cached;
        }
        String lock = key + ":refresh";
        String owner = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, owner, Duration.ofSeconds(30)))) {
            throw new ChannelProviderException("CHANNEL_TOKEN_REFRESHING", "应用凭证正在刷新，请稍后重试。");
        }
        try {
            cached = cached(key);
            if (cached != null) {
                return cached;
            }
            var token = providers.require(application.providerCode()).fetchAccessToken(application,
                secrets.load(application.enterpriseId(), application.id()));
            long seconds = token.expiresInSeconds();
            long margin = Math.min(60, Math.max(1, seconds / 10));
            if (seconds > margin) {
                long expiresAt = Math.addExact(clock.millis(), Math.multiplyExact(seconds, 1000L));
                String encrypted = encode(encryption.encrypt(new CachedToken(token.value(), expiresAt), key));
                redis.opsForValue().set(key, encrypted, Duration.ofSeconds(seconds - margin));
            }
            return token;
        } finally {
            redis.execute(DELETE_MATCHING, List.of(lock), owner);
        }
    }

    public void invalidate(IntegrationApplication application, ChannelAccessToken used) {
        String key = key(application);
        String encoded = redis.opsForValue().get(key);
        if (encoded != null && decode(key, encoded).value().equals(used.value())) {
            redis.execute(DELETE_MATCHING, List.of(key), encoded);
        }
    }

    private ChannelAccessToken cached(String key) {
        String encoded = redis.opsForValue().get(key);
        if (encoded == null) {
            return null;
        }
        var value = decode(key, encoded);
        long remaining = (value.expiresAtMillis() - clock.millis()) / 1000;
        if (remaining <= 0) {
            redis.execute(DELETE_MATCHING, List.of(key), encoded);
            return null;
        }
        return new ChannelAccessToken(value.value(), remaining);
    }

    private CachedToken decode(String key, String value) {
        try {
            return encryption.decrypt(json.readValue(value, EncryptedPayload.class), key, CachedToken.class);
        } catch (JsonProcessingException failure) {
            throw new ChannelProviderException("CHANNEL_TOKEN_CACHE_INVALID", "应用凭证缓存无法读取，请稍后重试。", failure);
        }
    }

    private String encode(EncryptedPayload value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("应用凭证无法保存到加密缓存", failure);
        }
    }

    private String key(IntegrationApplication application) {
        return "agenteam:integration:token:" + application.enterpriseId() + ":" + application.id() + ":" + application.credentialRevision();
    }

    private record CachedToken(String value, long expiresAtMillis) {
        @Override
        public String toString() {
            return "CachedToken[应用凭证已隐藏]";
        }
    }
}
