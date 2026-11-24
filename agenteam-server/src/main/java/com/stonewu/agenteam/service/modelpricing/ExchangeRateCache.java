package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.modelpricing.ExchangeRateClient.RateTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 共享缓存主备两个实时汇率源，避免每次导入价格弹窗重复请求外部汇率接口。 */
@Component
public class ExchangeRateCache {
    private static final Logger LOG = LoggerFactory.getLogger(ExchangeRateCache.class);
    private static final Duration FRESH_FOR = Duration.ofHours(12);
    private static final Duration RETAIN_FOR = Duration.ofDays(7);
    private final Slot primary;
    private final Slot fallback;

    public ExchangeRateCache(ExchangeRateClient client, Clock clock) {
        this.primary = new Slot(ExchangeRateClient.FRANKFURTER_NAME, client::fetchPrimary, clock);
        this.fallback = new Slot(ExchangeRateClient.OPEN_ER_NAME, client::fetchFallback, clock);
    }

    public Snapshot loadPrimary() {
        return primary.load();
    }

    public Snapshot loadFallback() {
        return fallback.load();
    }

    public record Snapshot(RateTable table, Instant fetchedAt, boolean stale) {
    }

    private static final class Slot {
        private final String sourceName;
        private final Supplier<RateTable> loader;
        private final Clock clock;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Snapshot snapshot;
        private volatile Instant retryAfter = Instant.MIN;
        private volatile RuntimeException lastFailure;

        private Slot(String sourceName, Supplier<RateTable> loader, Clock clock) {
            this.sourceName = sourceName;
            this.loader = loader;
            this.clock = clock;
        }

        private Snapshot load() {
            var current = snapshot;
            Instant now = clock.instant();
            if (current != null && now.isBefore(current.fetchedAt().plus(FRESH_FOR))) {
                return current;
            }
            if (now.isBefore(retryAfter)) {
                return available(current, now);
            }
            boolean acquired = lock.tryLock();
            if (!acquired && current == null) {
                try {
                    acquired = lock.tryLock(12, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    LOG.warn(Messages.log("log.modelpricing.exchangeRateCache.load.1"), sourceName, failure);
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXCHANGE_RATE_UNAVAILABLE",
                        Messages.text("error.modelpricing.exchangeRateCache.load.1"), failure);
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
                    var table = loader.get();
                    snapshot = new Snapshot(table, clock.instant(), false);
                    lastFailure = null;
                    retryAfter = Instant.MIN;
                    return snapshot;
                } catch (RuntimeException failure) {
                    lastFailure = failure;
                    retryAfter = clock.instant().plus(Duration.ofMinutes(5));
                    return available(current, clock.instant());
                }
            } finally {
                lock.unlock();
            }
        }

        private Snapshot available(Snapshot current, Instant now) {
            if (current != null && now.isBefore(current.fetchedAt().plus(RETAIN_FOR))) {
                return new Snapshot(current.table(), current.fetchedAt(), true);
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXCHANGE_RATE_UNAVAILABLE",
                Messages.text("error.modelpricing.exchangeRateCache.available.1"), lastFailure);
        }
    }
}
