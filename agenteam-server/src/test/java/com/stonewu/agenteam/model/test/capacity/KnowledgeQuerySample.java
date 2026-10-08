package com.stonewu.agenteam.model.test.capacity;

import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;

import java.util.List;

/**
 * 容量查询诊断只重放明确的业务参数，绝不接收完整语句。
 */
public record KnowledgeQuerySample(String enterprise, String resource, String terms,
                                   List<KnowledgeDocumentRow> versions, List<String> ids, int limit) {
    public KnowledgeQuerySample {
        versions = List.copyOf(versions);
        ids = List.copyOf(ids);
    }
}
