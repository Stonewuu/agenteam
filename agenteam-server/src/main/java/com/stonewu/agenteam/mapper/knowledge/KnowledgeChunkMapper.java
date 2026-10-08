package com.stonewu.agenteam.mapper.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.model.file.entity.DocumentChunk;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeChunkRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeCitationRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRecord;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import com.stonewu.agenteam.model.knowledge.response.CitationView;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 关键词查询只读取完整切换后的版本，返回原文件位置与原文片段。
 */
@Repository
public class KnowledgeChunkMapper {
    private final KnowledgeChunkSqlMapper statements;
    private final KnowledgeDocumentSqlMapper documents;
    private final ObjectMapper json;

    public KnowledgeChunkMapper(KnowledgeChunkSqlMapper statements, KnowledgeDocumentSqlMapper documents,
                                ObjectMapper json) {
        this.statements = statements;
        this.documents = documents;
        this.json = json;
    }

    public void clearPending(KnowledgeDocumentRecord document) {
        statements.delete(pending(document));
    }

    public void add(KnowledgeDocumentRecord doc, List<DocumentChunk> chunks, Instant now) {
        for (int start = 0; start < chunks.size(); start += 100) {
            var batch = chunks.subList(start, Math.min(start + 100, chunks.size())).stream().map(chunk -> {
                var locator = json.createObjectNode();
                if (chunk.page() != null) {
                    locator.put("page", chunk.page());
                } else {
                    locator.putNull("page");
                }
                if (chunk.section() != null) {
                    locator.put("section", chunk.section());
                } else {
                    locator.putNull("section");
                }
                var row = new KnowledgeChunkRow();
                row.setId(UUID.randomUUID().toString());
                row.setEnterpriseId(doc.enterpriseId());
                row.setDocumentId(doc.id());
                row.setFileId(doc.fileId());
                row.setGeneration(doc.pendingGeneration());
                row.setOrdinal(chunk.ordinal());
                row.setLocatorJson(locator.toString());
                row.setContentText(chunk.text());
                row.setContentHash(chunk.sha256());
                row.setCreatedAt(now);
                row.setSearchTerms(KnowledgeSearchTerms.indexed(doc.enterpriseId(), doc.resourceId(), chunk.text()));
                return row;
            }).toList();
            statements.insert(batch, 100);
        }
    }

    public int count(KnowledgeDocumentRecord doc) {
        return statements.selectCount(pending(doc)).intValue();
    }

    public void removeOldSearchTerms(KnowledgeDocumentRecord doc) {
        statements.update(new LambdaUpdateWrapper<KnowledgeChunkRow>()
            .eq(KnowledgeChunkRow::getEnterpriseId, doc.enterpriseId()).eq(KnowledgeChunkRow::getDocumentId, doc.id())
            .lt(KnowledgeChunkRow::getGeneration, doc.pendingGeneration()).isNotNull(KnowledgeChunkRow::getSearchTerms)
            .set(KnowledgeChunkRow::getSearchTerms, null));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CitationView> search(String enterprise, String resource, String query, int limit) {
        String terms = KnowledgeSearchTerms.query(enterprise, resource, query);
        if (terms.isEmpty()) {
            return List.of();
        }
        var versions = documents.selectJoinList(KnowledgeDocumentRow.class,
            JoinWrappers.lambda(KnowledgeDocumentRow.class)
                .select(KnowledgeDocumentRow::getId, KnowledgeDocumentRow::getActiveFileId,
                    KnowledgeDocumentRow::getActiveGeneration)
                .innerJoin(FileObjectRow.class,
                    on -> on.eq(FileObjectRow::getEnterpriseId, KnowledgeDocumentRow::getEnterpriseId)
                        .eq(FileObjectRow::getId, KnowledgeDocumentRow::getActiveFileId))
                .eq(KnowledgeDocumentRow::getEnterpriseId, enterprise).eq(KnowledgeDocumentRow::getResourceId, resource)
                .isNull(KnowledgeDocumentRow::getDeletedAt).gt(KnowledgeDocumentRow::getActiveGeneration, 0)
                .eq(FileObjectRow::getStatus, "ready").isNull(FileObjectRow::getDeletedAt));
        if (versions.isEmpty()) {
            return List.of();
        }
        var ids = statements.searchIds(enterprise, terms, versions, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        var citations = statements.selectJoinList(KnowledgeCitationRow.class, citationQuery(enterprise, true)
                .eq(KnowledgeDocumentRow::getResourceId, resource).in(KnowledgeChunkRow::getId, ids))
            .stream().map(this::citation).collect(Collectors.toMap(CitationView::chunkId, Function.identity()));
        return ids.stream().map(citations::get).filter(Objects::nonNull).toList();
    }

    public List<CitationView> reference(String enterprise, String document, int generation) {
        var query = citationQuery(enterprise, false).eq(KnowledgeDocumentRow::getId, document)
            .eq(KnowledgeChunkRow::getGeneration, generation).orderByAsc(KnowledgeChunkRow::getOrdinal);
        return statements.selectJoinPage(new Page<KnowledgeCitationRow>(1, 8, false), KnowledgeCitationRow.class, query)
            .getRecords().stream().map(this::citation).toList();
    }

    public record CitationSource(String resourceId, CitationView citation) {
    }

    public Optional<CitationSource> original(String enterprise, String chunk) {
        return statements.selectJoinList(KnowledgeCitationRow.class, citationQuery(enterprise, false)
                .eq(KnowledgeChunkRow::getId, chunk)).stream()
            .map(row -> new CitationSource(row.getResourceId(), citation(row))).findFirst();
    }

    private LambdaQueryWrapper<KnowledgeChunkRow> pending(KnowledgeDocumentRecord doc) {
        return new LambdaQueryWrapper<KnowledgeChunkRow>().eq(KnowledgeChunkRow::getEnterpriseId, doc.enterpriseId())
            .eq(KnowledgeChunkRow::getDocumentId, doc.id())
            .eq(KnowledgeChunkRow::getGeneration, doc.pendingGeneration());
    }

    private MPJLambdaWrapper<KnowledgeChunkRow> citationQuery(String enterprise, boolean currentVersion) {
        return JoinWrappers.lambda(KnowledgeChunkRow.class)
            .selectAs(KnowledgeChunkRow::getId, KnowledgeCitationRow::getChunkId)
            .select(KnowledgeChunkRow::getDocumentId, KnowledgeChunkRow::getFileId, KnowledgeChunkRow::getGeneration,
                KnowledgeChunkRow::getLocatorJson, KnowledgeChunkRow::getContentText)
            .select(KnowledgeDocumentRow::getResourceId)
            .selectAs(FileObjectRow::getOriginalName, KnowledgeCitationRow::getName)
            .innerJoin(KnowledgeDocumentRow.class, on -> {
                on.eq(KnowledgeDocumentRow::getEnterpriseId, KnowledgeChunkRow::getEnterpriseId)
                    .eq(KnowledgeDocumentRow::getId, KnowledgeChunkRow::getDocumentId)
                    .le(KnowledgeChunkRow::getGeneration, KnowledgeDocumentRow::getActiveGeneration);
                if (currentVersion) {
                    on.eq(KnowledgeDocumentRow::getActiveGeneration, KnowledgeChunkRow::getGeneration)
                        .eq(KnowledgeDocumentRow::getActiveFileId, KnowledgeChunkRow::getFileId);
                }
                return on;
            })
            .innerJoin(FileObjectRow.class,
                on -> on.eq(FileObjectRow::getEnterpriseId, KnowledgeChunkRow::getEnterpriseId)
                    .eq(FileObjectRow::getId, KnowledgeChunkRow::getFileId))
            .eq(KnowledgeChunkRow::getEnterpriseId, enterprise).isNull(KnowledgeDocumentRow::getDeletedAt)
            .eq(FileObjectRow::getStatus, "ready").isNull(FileObjectRow::getDeletedAt);
    }

    private CitationView citation(KnowledgeCitationRow row) {
        try {
            var locator = json.readTree(row.getLocatorJson());
            return new CitationView(row.getChunkId(), row.getDocumentId(), row.getFileId(), row.getGeneration(),
                row.getName(),
                locator.path("page").canConvertToInt() ? locator.path("page").asInt() : null,
                locator.path("section").isTextual() ? locator.path("section").asText() : null, row.getContentText());
        } catch (Exception invalid) {
            throw new IllegalStateException("保存的资料位置无法读取", invalid);
        }
    }
}
