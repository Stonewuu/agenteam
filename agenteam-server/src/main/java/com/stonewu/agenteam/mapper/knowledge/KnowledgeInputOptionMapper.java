package com.stonewu.agenteam.mapper.knowledge;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.knowledge.response.InputDocumentOptionView;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按当前使用授权和固定知识资源筛选后再分页，只列出处理完成的可读文档。
 */
@Repository
public class KnowledgeInputOptionMapper {
    public record Candidate(InputDocumentOptionView option, Instant updatedAt) {
    }

    private final KnowledgeInputOptionSqlMapper statements;

    public KnowledgeInputOptionMapper(KnowledgeInputOptionSqlMapper statements) {
        this.statements = statements;
    }

    public List<Candidate> list(ResourceQueryScope scope, Map<String, String> allowed, String query,
                                PagePosition cursor, int limit) {
        if (allowed.isEmpty()) {
            return List.of();
        }
        return statements.listDocuments(scope, new ArrayList<>(allowed.keySet()), query, cursor, limit + 1).stream()
            .map(row -> new Candidate(
                new InputDocumentOptionView("document", row.getId(), row.getActiveGeneration(), row.getName(),
                    allowed.get(row.getResourceId())),
                row.getUpdatedAt().toInstant())).toList();
    }
}
