package com.stonewu.agenteam.capacity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从实际事务提交到本机客户端读到事件计时；连接建立前的历史事件不参与实时延迟。
 */
final class ExecutionStreamMeasurements {
    private final Map<String, Long> committedEvents = new ConcurrentHashMap<>();
    private final Map<String, Long> acceptedRuns = new ConcurrentHashMap<>();
    private final Map<String, Long> startedRuns = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> phases = new LinkedHashMap<>();
    private final List<Double> eventMillis = new ArrayList<>();
    private final List<Double> queueMillis = new ArrayList<>();
    private volatile boolean active;

    public void start() {
        active = true;
    }

    public void stop() {
        active = false;
    }

    public boolean active() {
        return active;
    }

    public void saved(ExecutionEvent event) {
        if (!active) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                long now = System.nanoTime();
                committedEvents.put(event.eventId(), now);
                if (event.type().equals("run.created")) {
                    acceptedRuns.put(event.runId(), now);
                }
                if (event.type().equals("run.started")) {
                    startedRuns.put(event.runId(), now);
                }
            }
        });
    }

    public synchronized void duration(String phase, long nanos) {
        phases.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(nanos / 1_000_000.0);
    }

    public synchronized void received(JsonNode event, long readyAt) {
        Long committed = committedEvents.remove(event.path("eventId").asText());
        if (committed != null && committed >= readyAt) {
            eventMillis.add((System.nanoTime() - committed) / 1_000_000.0);
        }
    }

    public synchronized void started(String run, long modelStarted) {
        Long accepted = acceptedRuns.remove(run);
        if (accepted == null || modelStarted < accepted) {
            throw new IllegalStateException("执行开始没有对应的已提交请求");
        }
        Long started = startedRuns.remove(run);
        if (started == null || started < accepted || modelStarted < started) {
            throw new IllegalStateException("工作进程开始状态与模型调用顺序不正确");
        }
        queueMillis.add((started - accepted) / 1_000_000.0);
        duration("提交至模型收到请求", modelStarted - accepted);
        duration("保存开始状态至模型收到请求", modelStarted - started);
    }

    public void clearCompletedEvents() {
        committedEvents.clear();
        acceptedRuns.clear();
        startedRuns.clear();
    }

    public synchronized Map<String, Object> report() {
        var details = new LinkedHashMap<String, Object>();
        phases.forEach((name, values) -> {
            var stats = new LinkedHashMap<>(summarize(values, Double.MAX_VALUE));
            stats.remove("passed");
            stats.remove("limitMillis");
            details.put(name, stats);
        });
        return Map.of("queue", summarize(queueMillis, 2000), "events", summarize(eventMillis, 300), "phases", details);
    }

    public synchronized boolean passed() {
        return Boolean.TRUE.equals(summarize(queueMillis, 2000).get("passed")) && Boolean.TRUE.equals(summarize(eventMillis, 300).get("passed"));
    }

    private Map<String, Object> summarize(List<Double> values, double limit) {
        if (values.isEmpty()) {
            return Map.of("samples", 0, "limitMillis", limit, "passed", false);
        }
        var sorted = values.stream().sorted().toList();
        double p95 = sorted.get((int) Math.ceil(sorted.size() * .95) - 1);
        return Map.of("samples", sorted.size(), "p50Millis", sorted.get(sorted.size() / 2), "p95Millis", p95, "maximumMillis", sorted.getLast(), "limitMillis", limit, "passed", p95 < limit);
    }
}
