package com.stonewu.agenteam.mapper.knowledge;


import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRecord;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * 先分批删除不可访问资料的文字，再释放原文件的保存关系。
 */
@Repository
public class KnowledgeRetentionMapper {
    private final KnowledgeRetentionSqlMapper statements;

    private final KnowledgeChunkSqlMapper knowledgeChunkSqlMapper;

    public KnowledgeRetentionMapper(KnowledgeRetentionSqlMapper statements,
                                    KnowledgeChunkSqlMapper knowledgeChunkSqlMapper) {
        this.knowledgeChunkSqlMapper = knowledgeChunkSqlMapper;
        this.statements = statements;
    }

    public record Candidate(String enterprise, String document) {
    }

    public List<Candidate> candidates() {
        return statements.candidatesKnowledgeDocument().stream()
            .map(row -> new Candidate(row.getEnterpriseId(), row.getId())).toList();
    }

    public boolean remove(KnowledgeDocumentRecord doc, Instant now) {
        statements.removeBackgroundJob(Timestamp.from(now), doc.enterpriseId(), doc.id());
        var affectedFiles = new HashSet<>(knowledgeChunkSqlMapper.removeKnowledgeChunk(doc.enterpriseId(), doc.id()));
        int removed = statements.removeKnowledgeChunk2(doc.enterpriseId(), doc.id());
        if (removed < 500) {
            statements.removeKnowledgeDocument(doc.enterpriseId(), doc.id());
            affectedFiles.add(doc.fileId());
            if (doc.activeFileId() != null) {
                affectedFiles.add(doc.activeFileId());
            }
        }
        for (String file : affectedFiles) {
            releaseUnreferencedFile(doc.enterpriseId(), file, now);
        }
        return removed < 500;
    }

    public void releaseUnreferencedFile(String enterprise, String file, Instant now) {
        statements.releaseUnreferencedFileFileObject(Timestamp.from(now), enterprise, file);
    }
}
