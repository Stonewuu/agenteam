package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCatalogEntry;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

/** 所有用户共享一份目录；刷新失败有等待间隔，避免故障时反复下载。 */
@Component
public class ModelsDevCatalogCache {
    private static final Logger LOG = LoggerFactory.getLogger(ModelsDevCatalogCache.class);
    private static final Duration FRESH_FOR = Duration.ofHours(12);
    private static final Duration RETAIN_FOR = Duration.ofDays(7);
    private final ModelsDevCatalogClient client;
    private final Clock clock;
    private final ReentrantLock refresh = new ReentrantLock();
    private volatile Snapshot snapshot;
    private volatile Instant retryAfter = Instant.MIN;
    private volatile RuntimeException lastFailure;

    public ModelsDevCatalogCache(ModelsDevCatalogClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public Snapshot load(String modelId) {
        var current = snapshot;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.fetchedAt().plus(FRESH_FOR))) {
            return current;
        }
        if (now.isBefore(retryAfter)) {
            return available(current, now);
        }
        boolean acquired = refresh.tryLock();
        if (!acquired && current == null) {
            try {
                acquired = refresh.tryLock(16, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                LOG.warn("等待价格目录被中断，模型={}", modelId, failure);
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PRICE_CATALOG_UNAVAILABLE", "价格读取已中断，请重试。", failure);
            }
        }
        if (!acquired) {
            return available(snapshot, clock.instant());
        }
        try {
            now = clock.instant();
            current = snapshot;
            if (current != null && now.isBefore(current.fetchedAt().plus(FRESH_FOR))) {
                return current;
            }
            if (now.isBefore(retryAfter)) {
                return available(current, now);
            }
            try {
                var entries = client.fetch(modelId);
                snapshot = new Snapshot(entries, clock.instant(), false);
                lastFailure = null;
                retryAfter = Instant.MIN;
                return snapshot;
            } catch (RuntimeException failure) {
                // 客户端已记录脱敏原因和完整异常堆栈，此处不重复打印第三方错误。
                lastFailure = failure;
                retryAfter = clock.instant().plus(Duration.ofMinutes(5));
                return available(current, clock.instant());
            }
        } finally {
            refresh.unlock();
        }
    }

    private Snapshot available(Snapshot current, Instant now) {
        if (current != null && now.isBefore(current.fetchedAt().plus(RETAIN_FOR))) {
            return new Snapshot(current.entries(), current.fetchedAt(), true);
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PRICE_CATALOG_UNAVAILABLE",
            "暂时无法读取 models.dev 价格，可稍后重试或手动配置。", lastFailure);
    }

    public record Snapshot(List<ModelPriceCatalogEntry> entries, Instant fetchedAt, boolean stale) {
    }
}
