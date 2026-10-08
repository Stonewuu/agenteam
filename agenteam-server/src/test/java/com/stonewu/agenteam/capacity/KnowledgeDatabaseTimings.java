package com.stonewu.agenteam.capacity;

import com.stonewu.agenteam.mapper.knowledge.KnowledgeSearchTerms;
import com.stonewu.agenteam.mapper.test.capacity.KnowledgePlanFixtureMapper;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import com.stonewu.agenteam.model.test.capacity.KnowledgeQuerySample;
import com.stonewu.agenteam.support.MybatisTestDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录实际知识查询的三个阶段，并以明确参数重放固定诊断方案。
 */
public final class KnowledgeDatabaseTimings {
    private static final String VERSIONS = "当前资料版本";
    private static final String RANKS = "全文查找与排序";
    private static final String CITATIONS = "引用正文读取";
    private final Map<String, ArrayList<Double>> samples = new LinkedHashMap<>();
    private final Map<String, Query> queries = new LinkedHashMap<>();
    private final ThreadLocal<Search> current = new ThreadLocal<>();
    private boolean recording = true;

    private record Query(String phase, String sql, KnowledgeQuerySample sample) {
    }

    private static final class Search {
        private final String enterprise;
        private final String resource;
        private final String terms;
        private final int limit;
        private List<KnowledgeDocumentRow> versions = List.of();
        private List<String> ids = List.of();

        private Search(String enterprise, String resource, String query, int limit) {
            this.enterprise = enterprise;
            this.resource = resource;
            this.limit = limit;
            this.terms = KnowledgeSearchTerms.query(enterprise, resource, query);
        }

        private KnowledgeQuerySample sample() {
            return new KnowledgeQuerySample(enterprise, resource, terms, versions, ids, limit);
        }
    }

    public AutoCloseable begin(String enterprise, String resource, String query, int limit) {
        Search previous = current.get();
        current.set(new Search(enterprise, resource, query, limit));
        return () -> {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        };
    }

    public boolean inSearch() {
        return current.get() != null;
    }

    public synchronized void record(String statement, String sql, Object parameters, Object result, long elapsed) {
        Search search = current.get();
        if (!recording || search == null || !(result instanceof List<?> rows)) {
            return;
        }
        String phase;
        if (statement.endsWith("KnowledgeDocumentSqlMapper.selectJoinList")) {
            search.versions = rows.stream().map(KnowledgeDocumentRow.class::cast).toList();
            phase = VERSIONS;
        } else if (statement.endsWith("KnowledgeChunkSqlMapper.searchIds")) {
            search.ids = rows.stream().map(String.class::cast).toList();
            phase = RANKS;
        } else if (statement.endsWith("KnowledgeChunkSqlMapper.selectJoinList") && !search.ids.isEmpty()) {
            phase = CITATIONS;
        } else {
            return;
        }
        samples.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(elapsed / 1_000_000.0);
        if (!rows.isEmpty()) {
            queries.putIfAbsent(phase + "：" + search.enterprise, new Query(phase, sql, search.sample()));
        }
    }

    public synchronized void clearSamples() {
        samples.clear();
        queries.clear();
    }

    public synchronized void stopRecording() {
        recording = false;
    }

    public synchronized Map<String, Object> report() {
        if (!samples.keySet().containsAll(List.of(VERSIONS, RANKS, CITATIONS))) {
            throw new IllegalStateException("容量验收没有记录完整的知识查询阶段，已记录：" + samples.keySet());
        }
        var result = new LinkedHashMap<String, Object>();
        samples.forEach((name, values) -> {
            var sorted = values.stream().sorted().toList();
            result.put(name, Map.of("samples", sorted.size(), "p50Millis", sorted.get(sorted.size() / 2),
                "p95Millis", sorted.get((int) Math.ceil(sorted.size() * .95) - 1), "maximumMillis", sorted.getLast()));
        });
        return result;
    }

    public synchronized Map<String, Object> plans(MybatisTestDatabase database) {
        var mapper = database.mapper(KnowledgePlanFixtureMapper.class);
        var result = new LinkedHashMap<String, Object>();
        queries.forEach((name, query) -> result.put(name, switch (query.phase()) {
            case VERSIONS -> mapper.explainVersions(query.sample());
            case RANKS -> mapper.explainRanks(query.sample(), "CURRENT");
            case CITATIONS -> mapper.explainCitations(query.sample());
            default -> throw new IllegalStateException("没有对应的知识查询诊断");
        }));
        return result;
    }

    public synchronized Map<String, Object> compareKnowledgeQueries(MybatisTestDatabase database) {
        var mapper = database.mapper(KnowledgePlanFixtureMapper.class);
        var result = new LinkedHashMap<String, Object>();
        queries.forEach((name, query) -> {
            if (!query.phase().equals(RANKS)) {
                return;
            }
            var comparison = new LinkedHashMap<String, Object>();
            for (String variant : List.of("CURRENT", "EXPANDED", "BOOLEAN")) {
                var elapsed = new ArrayList<Double>();
                List<Map<String, Object>> rows = List.of();
                for (int repeat = 0; repeat < 2; repeat++) {
                    long started = System.nanoTime();
                    rows = mapper.compareRanks(query.sample(), variant);
                    elapsed.add((System.nanoTime() - started) / 1_000_000.0);
                }
                String label = switch (variant) {
                    case "CURRENT" -> "当前查询";
                    case "EXPANDED" -> "逐项检查相同版本";
                    default -> "普通检索词使用布尔全文模式";
                };
                comparison.put(label, Map.of("millis", elapsed, "rows", rows, "plan", mapper.explainRanks(query.sample(), variant)));
            }
            result.put(name, Map.of("sql", query.sql(), "parameters", query.sample(), "variants", comparison));
        });
        return result;
    }
}
