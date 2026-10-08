package com.stonewu.agenteam.mapper.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeRetentionQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * KnowledgeRetentionMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface KnowledgeRetentionSqlMapper extends MPJBaseMapper<KnowledgeDocumentRow> {
    default List<KnowledgeRetentionQueryRow> candidatesKnowledgeDocument() {
        var criteria = new LambdaQueryWrapper<KnowledgeDocumentRow>().select(KnowledgeDocumentRow::getEnterpriseId,
                KnowledgeDocumentRow::getId).orderByAsc(KnowledgeDocumentRow::getDeletedAt)
            .orderByAsc(KnowledgeDocumentRow::getId).isNotNull(KnowledgeDocumentRow::getDeletedAt);
        long pageSize = 20;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<KnowledgeDocumentRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> {
                var mappedRow = new KnowledgeRetentionQueryRow();
                if (storedRow.getEnterpriseId() != null) {
                    mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
                }
                if (storedRow.getId() != null) {
                    mappedRow.setId(storedRow.getId());
                }
                return mappedRow;
            }).toList();
    }

    int removeBackgroundJob(@Param("now") Timestamp now, @Param("enterpriseId") String enterpriseId,
                            @Param("id") String id);


    int removeKnowledgeChunk2(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    default int removeKnowledgeDocument(String enterpriseId, String id) {
        return delete(
            new LambdaQueryWrapper<KnowledgeDocumentRow>().eq(KnowledgeDocumentRow::getEnterpriseId, enterpriseId)
                .eq(KnowledgeDocumentRow::getId, id).isNotNull(KnowledgeDocumentRow::getDeletedAt));
    }

    int releaseUnreferencedFileFileObject(@Param("now") Timestamp now, @Param("enterprise") String enterprise,
                                          @Param("file") String file);
}
