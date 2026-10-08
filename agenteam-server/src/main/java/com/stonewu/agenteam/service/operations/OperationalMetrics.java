package com.stonewu.agenteam.service.operations;

import com.stonewu.agenteam.mapper.operations.OperationalStateMapper;
import com.stonewu.agenteam.model.operations.entity.OperationalSnapshot;
import com.stonewu.agenteam.model.operations.entity.QuotaCheckEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 采集失败时撤下旧数量，不能把无法读取数据库显示为零或继续标为当前值。
 */
@Component
@ConditionalOnProperty(name = "operations.metrics.enabled", havingValue = "true")
public class OperationalMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(OperationalMetrics.class);
    private final OperationalQueryService queries;
    private final Counter failures;
    private final Counter quotaSuccess;
    private final Counter quotaFailures;
    private final Counter quotaDifferences;
    private volatile OperationalSnapshot snapshot;
    private volatile double lastSuccess = Double.NaN;
    private volatile double lastQuotaCheck = Double.NaN;

    public OperationalMetrics(OperationalQueryService queries, MeterRegistry registry) {
        this.queries = queries;
        failures = registry.counter("agenteam.metrics.collection.failures");
        quotaSuccess = registry.counter("agenteam.quota.checks", "result", "success");
        quotaFailures = registry.counter("agenteam.quota.checks", "result", "failure");
        quotaDifferences = registry.counter("agenteam.quota.differences");
        Gauge.builder("agenteam.metrics.collection.available", this, value -> value.snapshot == null ? 0 : 1)
            .register(registry);
        Gauge.builder("agenteam.metrics.last.success", this, value -> value.lastSuccess).baseUnit("seconds")
            .register(registry);
        Gauge.builder("agenteam.quota.last.check", this, value -> value.lastQuotaCheck).baseUnit("seconds")
            .register(registry);
        for (String kind : OperationalStateMapper.JOB_KINDS) {
            for (String status : new String[]{"queued", "ready", "leased", "expired"}) {
                Gauge.builder("agenteam.background.jobs", this, value -> value.value("job." + kind + "." + status))
                    .tags("kind", kind, "status", status).register(registry);
            }
            Gauge.builder("agenteam.background.oldest", this, value -> value.value("job." + kind + ".oldest_seconds"))
                .tag("kind", kind).baseUnit("seconds").register(registry);
        }
        for (String status : OperationalStateMapper.ACTIVE_RUN_STATES) {
            Gauge.builder("agenteam.executions", this, value -> value.value("run." + status)).tag("status", status)
                .register(registry);
        }
        gauge(registry, "agenteam.events.pending", "event.pending");
        Gauge.builder("agenteam.events.oldest", this, value -> value.value("event.oldest_seconds")).baseUnit("seconds")
            .register(registry);
        gauge(registry, "agenteam.files.rejected", "file.rejected");
        gauge(registry, "agenteam.knowledge.failed", "knowledge.failed");
        gauge(registry, "agenteam.tools.unknown", "tool.unknown");
    }

    private void gauge(MeterRegistry registry, String name, String key) {
        Gauge.builder(name, this, value -> value.value(key)).register(registry);
    }

    private double value(String key) {
        var current = snapshot;
        return current == null ? Double.NaN : current.values().getOrDefault(key, Double.NaN);
    }

    @Scheduled(fixedDelayString = "${operations.metrics.interval-ms:15000}", initialDelayString = "${operations.metrics.initial-delay-ms:15000}")
    public void collect() {
        try {
            var current = queries.read();
            snapshot = current;
            lastSuccess = current.readAt().toEpochMilli() / 1000.0;
        } catch (RuntimeException unavailable) {
            snapshot = null;
            failures.increment();
            LOG.warn("运维状态暂时无法采集，下一轮重试", unavailable);
        }
    }

    @EventListener
    public void quotaChecked(QuotaCheckEvent event) {
        if (event.successful()) {
            quotaSuccess.increment();
            quotaDifferences.increment(event.differences());
            lastQuotaCheck = event.finishedAt().toEpochMilli() / 1000.0;
        } else {
            quotaFailures.increment();
        }
    }
}
