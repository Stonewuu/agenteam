package com.stonewu.agenteam.capacity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 使用本机单调时钟计时，保留样本数和规定的第九十五百分位阈值。
 */
public final class CapacityMeasurements {
    private record Samples(double limit, List<Double> values) {
    }

    private final Map<String, Samples> samples = new LinkedHashMap<>();

    public <T> T measure(String name, double limitMillis, Callable<T> operation) throws Exception {
        long start = System.nanoTime();
        T result = operation.call();
        samples.computeIfAbsent(name, ignored -> new Samples(limitMillis, new ArrayList<>())).values().add((System.nanoTime() - start) / 1_000_000.0);
        return result;
    }

    public Map<String, Object> report() {
        var result = new LinkedHashMap<String, Object>();
        samples.forEach((name, sample) -> {
            var sorted = sample.values().stream().sorted().toList();
            double p95 = sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * .95) - 1));
            result.put(name, Map.of("samples", sorted.size(), "p50Millis", sorted.get(sorted.size() / 2), "p95Millis", p95,
                "maximumMillis", sorted.getLast(), "limitMillis", sample.limit(), "passed", p95 < sample.limit()));
        });
        return result;
    }

    public boolean passed() {
        return report().values().stream().allMatch(value -> Boolean.TRUE.equals(((Map<?, ?>) value).get("passed")));
    }
}
