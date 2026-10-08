package com.stonewu.agenteam.mapper.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeChunkRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分段批量写入与受当前文档版本限制的全文查询。
 */
@Mapper
public interface KnowledgeChunkSqlMapper extends MPJBaseMapper<KnowledgeChunkRow> {

    List<String> searchIds(@Param("enterprise") String enterprise, @Param("terms") String terms,
                           @Param("versions") List<KnowledgeDocumentRow> versions, @Param("limit") int limit);

    default int deleteKnowledgeChunk(String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<KnowledgeChunkRow>().eq(KnowledgeChunkRow::getEnterpriseId, enterpriseId)
            .eq(KnowledgeChunkRow::getDocumentId, id).isNotNull(KnowledgeChunkRow::getSearchTerms)
            .set(KnowledgeChunkRow::getSearchTerms, null));
    }

    default List<String> removeKnowledgeChunk(String enterpriseId, String id) {
        var criteria = new LambdaQueryWrapper<KnowledgeChunkRow>().select(KnowledgeChunkRow::getFileId)
            .orderByAsc(KnowledgeChunkRow::getId).eq(KnowledgeChunkRow::getEnterpriseId, enterpriseId)
            .eq(KnowledgeChunkRow::getDocumentId, id);
        long pageSize = 500;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<KnowledgeChunkRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> storedRow.getFileId()).toList();
    }
}
