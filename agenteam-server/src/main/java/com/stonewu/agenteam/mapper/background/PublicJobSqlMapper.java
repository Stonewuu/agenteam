package com.stonewu.agenteam.mapper.background;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.background.entity.PublicJobQueryRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

/**
 * PublicJobMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface PublicJobSqlMapper extends MPJBaseMapper<BackgroundJobRow> {
    default List<PublicJobQueryRow> findBackgroundJob(String enterprise, String id) {
        var criteria = new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getId,
                BackgroundJobRow::getEnterpriseId, BackgroundJobRow::getOwnerUserId, BackgroundJobRow::getKind,
                BackgroundJobRow::getPayloadJson, BackgroundJobRow::getStatus, BackgroundJobRow::getResultFileId,
                BackgroundJobRow::getErrorSummary, BackgroundJobRow::getCreatedAt, BackgroundJobRow::getUpdatedAt)
            .eq(BackgroundJobRow::getEnterpriseId, enterprise).eq(BackgroundJobRow::getId, id)
            .in(BackgroundJobRow::getKind, Arrays.asList("file_scan", "document_parse", "export"));
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new PublicJobQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getOwnerUserId() != null) {
                mappedRow.setOwnerUserId(storedRow.getOwnerUserId());
            }
            if (storedRow.getKind() != null) {
                mappedRow.setKind(storedRow.getKind());
            }
            if (storedRow.getPayloadJson() != null) {
                mappedRow.setPayloadJson(storedRow.getPayloadJson());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getResultFileId() != null) {
                mappedRow.setResultFileId(storedRow.getResultFileId());
            }
            if (storedRow.getErrorSummary() != null) {
                mappedRow.setErrorSummary(storedRow.getErrorSummary());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            if (storedRow.getUpdatedAt() != null) {
                mappedRow.setUpdatedAt(
                    (storedRow.getUpdatedAt() == null ? null : Timestamp.from(storedRow.getUpdatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    default List<PublicJobQueryRow> forExportFileBackgroundJob(String enterprise, String file) {
        var criteria = new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getId,
                BackgroundJobRow::getEnterpriseId, BackgroundJobRow::getOwnerUserId, BackgroundJobRow::getKind,
                BackgroundJobRow::getPayloadJson, BackgroundJobRow::getStatus, BackgroundJobRow::getResultFileId,
                BackgroundJobRow::getErrorSummary, BackgroundJobRow::getCreatedAt, BackgroundJobRow::getUpdatedAt)
            .eq(BackgroundJobRow::getEnterpriseId, enterprise).eq(BackgroundJobRow::getResultFileId, file)
            .eq(BackgroundJobRow::getKind, "export").eq(BackgroundJobRow::getStatus, "completed");
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new PublicJobQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getOwnerUserId() != null) {
                mappedRow.setOwnerUserId(storedRow.getOwnerUserId());
            }
            if (storedRow.getKind() != null) {
                mappedRow.setKind(storedRow.getKind());
            }
            if (storedRow.getPayloadJson() != null) {
                mappedRow.setPayloadJson(storedRow.getPayloadJson());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getResultFileId() != null) {
                mappedRow.setResultFileId(storedRow.getResultFileId());
            }
            if (storedRow.getErrorSummary() != null) {
                mappedRow.setErrorSummary(storedRow.getErrorSummary());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            if (storedRow.getUpdatedAt() != null) {
                mappedRow.setUpdatedAt(
                    (storedRow.getUpdatedAt() == null ? null : Timestamp.from(storedRow.getUpdatedAt())));
            }
            return mappedRow;
        }).toList();
    }
}
