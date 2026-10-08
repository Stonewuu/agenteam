package com.stonewu.agenteam.mapper.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.mapper.query.LikePattern;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentQueryRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * KnowledgeDocumentMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface KnowledgeDocumentSqlMapper extends MPJBaseMapper<KnowledgeDocumentRow> {
    default List<KnowledgeDocumentQueryRow> listDocuments(String enterprise, String resource, String text,
                                                          PagePosition position, int limit) {
        var query = documentQuery(enterprise).eq(KnowledgeDocumentRow::getResourceId, resource)
            .isNull(KnowledgeDocumentRow::getDeletedAt)
            .like(KnowledgeDocumentRow::getName, LikePattern.escapeWildcards(text));
        if (position != null) {
            query.and(part -> part.lt(KnowledgeDocumentRow::getUpdatedAt, position.time())
                .or(equal -> equal.eq(KnowledgeDocumentRow::getUpdatedAt, position.time())
                    .lt(KnowledgeDocumentRow::getId, position.id())));
        }
        query.orderByDesc(KnowledgeDocumentRow::getUpdatedAt, KnowledgeDocumentRow::getId);
        return selectJoinPage(new Page<KnowledgeDocumentQueryRow>(1, limit, false), KnowledgeDocumentQueryRow.class,
            query).getRecords();
    }

    private static MPJLambdaWrapper<KnowledgeDocumentRow> documentQuery(String enterprise) {
        return JoinWrappers.lambda(KnowledgeDocumentRow.class).selectAll(KnowledgeDocumentRow.class)
            .selectAs(FileObjectRow::getSizeBytes, KnowledgeDocumentQueryRow::getFileSizeBytes)
            .leftJoin(FileObjectRow.class,
                on -> on.eq(FileObjectRow::getEnterpriseId, KnowledgeDocumentRow::getEnterpriseId)
                    .eq(FileObjectRow::getId, KnowledgeDocumentRow::getFileId))
            .eq(KnowledgeDocumentRow::getEnterpriseId, enterprise);
    }

    default List<KnowledgeDocumentQueryRow> findFileObject(String enterprise, String id, boolean lock) {
        if (lock) {
            return findFileObjectLocked(enterprise, id);
        }
        return selectJoinList(KnowledgeDocumentQueryRow.class,
            documentQuery(enterprise).eq(KnowledgeDocumentRow::getId, id));
    }

    List<KnowledgeDocumentQueryRow> findFileObjectLocked(@Param("enterprise") String enterprise,
                                                         @Param("id") String id);

    default List<Integer> countKnowledgeDocument(String enterprise, String resource) {
        return List.of(Math.toIntExact(selectCount(
            new LambdaQueryWrapper<KnowledgeDocumentRow>().eq(KnowledgeDocumentRow::getEnterpriseId, enterprise)
                .eq(KnowledgeDocumentRow::getResourceId, resource).isNull(KnowledgeDocumentRow::getDeletedAt))));
    }

    default List<Integer> hasDocumentKnowledgeDocument(String enterprise, String resource, String file) {
        return List.of(Math.toIntExact(selectCount(
            new LambdaQueryWrapper<KnowledgeDocumentRow>().eq(KnowledgeDocumentRow::getEnterpriseId, enterprise)
                .eq(KnowledgeDocumentRow::getResourceId, resource).eq(KnowledgeDocumentRow::getFileId, file)
                .isNull(KnowledgeDocumentRow::getDeletedAt))));
    }

    List<Integer> referencesReadableFileKnowledgeDocument(@Param("enterprise") String enterprise,
                                                          @Param("resource") String resource,
                                                          @Param("file") String file);

    default int createKnowledgeDocument(String id, String enterpriseId, String resourceId, String originalName,
                                        String fileId, Timestamp now) {
        var databaseRow = new KnowledgeDocumentRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setResourceId(resourceId);
        databaseRow.setName(originalName);
        databaseRow.setFileId(fileId);
        databaseRow.setPendingGeneration(1);
        databaseRow.setLastGeneration(1);
        databaseRow.setStatus("queued");
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    int reprocessKnowledgeDocument(@Param("now") Timestamp now, @Param("enterpriseId") String enterpriseId,
                                   @Param("id") String id);

    default int replaceFileKnowledgeDocument(String id, String originalName, String enterpriseId, String id2) {
        return update(
            new LambdaUpdateWrapper<KnowledgeDocumentRow>().eq(KnowledgeDocumentRow::getEnterpriseId, enterpriseId)
                .eq(KnowledgeDocumentRow::getId, id2).set(KnowledgeDocumentRow::getFileId, id)
                .set(KnowledgeDocumentRow::getName, originalName));
    }

    default int processingKnowledgeDocument(Timestamp now, String enterpriseId, String id) {
        return update(
            new LambdaUpdateWrapper<KnowledgeDocumentRow>().eq(KnowledgeDocumentRow::getEnterpriseId, enterpriseId)
                .eq(KnowledgeDocumentRow::getId, id).set(KnowledgeDocumentRow::getStatus, "processing")
                .setIncrBy(KnowledgeDocumentRow::getRevision, 1).set(KnowledgeDocumentRow::getUpdatedAt, now));
    }

    int completeKnowledgeDocument(@Param("count") int count, @Param("pages") Integer pages, @Param("now") Timestamp now,
                                  @Param("enterpriseId") String enterpriseId, @Param("id") String id);

    default int failedKnowledgeDocument(String status, int pendingGeneration, String code, String summary,
                                        Timestamp now, String enterpriseId, String id) {
        return update(
            new LambdaUpdateWrapper<KnowledgeDocumentRow>().eq(KnowledgeDocumentRow::getEnterpriseId, enterpriseId)
                .eq(KnowledgeDocumentRow::getId, id).set(KnowledgeDocumentRow::getStatus, status)
                .set(KnowledgeDocumentRow::getPendingGeneration, pendingGeneration)
                .set(KnowledgeDocumentRow::getErrorCode, code).set(KnowledgeDocumentRow::getErrorSummary, summary)
                .setIncrBy(KnowledgeDocumentRow::getRevision, 1).set(KnowledgeDocumentRow::getUpdatedAt, now));
    }

    default int deleteKnowledgeDocument(Timestamp now, String enterpriseId, String id) {
        return update(
            new LambdaUpdateWrapper<KnowledgeDocumentRow>().eq(KnowledgeDocumentRow::getEnterpriseId, enterpriseId)
                .eq(KnowledgeDocumentRow::getId, id).set(KnowledgeDocumentRow::getStatus, "deleted")
                .set(KnowledgeDocumentRow::getDeletedAt, now).set(KnowledgeDocumentRow::getPendingGeneration, 0)
                .setIncrBy(KnowledgeDocumentRow::getRevision, 1).set(KnowledgeDocumentRow::getUpdatedAt, now));
    }


}
