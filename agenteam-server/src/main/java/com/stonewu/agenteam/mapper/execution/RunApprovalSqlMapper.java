package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.RunApprovalQueryRow;
import com.stonewu.agenteam.model.execution.entity.RunApprovalRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * run_id 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface RunApprovalSqlMapper extends MPJBaseMapper<RunApprovalRow> {
    default List<RunApprovalQueryRow> findRunApproval(String enterprise, String id, boolean lock) {
        if (lock) {
            return findRunApprovalLocked(enterprise, id, lock);
        }
        var criteria = new LambdaQueryWrapper<RunApprovalRow>().eq(RunApprovalRow::getEnterpriseId, enterprise)
            .eq(RunApprovalRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunApprovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getRunId() != null) {
                mappedRow.setRunId(storedRow.getRunId());
            }
            if (storedRow.getStepId() != null) {
                mappedRow.setStepId(storedRow.getStepId());
            }
            if (storedRow.getToolCallId() != null) {
                mappedRow.setToolCallId(storedRow.getToolCallId());
            }
            if (storedRow.getApproverUserId() != null) {
                mappedRow.setApproverUserId(storedRow.getApproverUserId());
            }
            if (storedRow.getRequestHash() != null) {
                mappedRow.setRequestHash(storedRow.getRequestHash());
            }
            if (storedRow.getSummaryJson() != null) {
                mappedRow.setSummaryJson(storedRow.getSummaryJson());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getDecidedAt() != null) {
                mappedRow.setDecidedAt(
                    (storedRow.getDecidedAt() == null ? null : Timestamp.from(storedRow.getDecidedAt())));
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }

    List<RunApprovalQueryRow> findRunApprovalLocked(@Param("enterprise") String enterprise, @Param("id") String id,
                                                    @Param("lock") boolean lock);

    default List<RunApprovalQueryRow> forToolRunApproval(String enterpriseId, String id) {
        var criteria = new LambdaQueryWrapper<RunApprovalRow>().eq(RunApprovalRow::getEnterpriseId, enterpriseId)
            .eq(RunApprovalRow::getToolCallId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunApprovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getRunId() != null) {
                mappedRow.setRunId(storedRow.getRunId());
            }
            if (storedRow.getStepId() != null) {
                mappedRow.setStepId(storedRow.getStepId());
            }
            if (storedRow.getToolCallId() != null) {
                mappedRow.setToolCallId(storedRow.getToolCallId());
            }
            if (storedRow.getApproverUserId() != null) {
                mappedRow.setApproverUserId(storedRow.getApproverUserId());
            }
            if (storedRow.getRequestHash() != null) {
                mappedRow.setRequestHash(storedRow.getRequestHash());
            }
            if (storedRow.getSummaryJson() != null) {
                mappedRow.setSummaryJson(storedRow.getSummaryJson());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getDecidedAt() != null) {
                mappedRow.setDecidedAt(
                    (storedRow.getDecidedAt() == null ? null : Timestamp.from(storedRow.getDecidedAt())));
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }

    default List<RunApprovalQueryRow> forRunRunApproval(String enterprise, String run) {
        var criteria = new LambdaQueryWrapper<RunApprovalRow>().orderByAsc(RunApprovalRow::getCreatedAt)
            .orderByAsc(RunApprovalRow::getId).eq(RunApprovalRow::getEnterpriseId, enterprise)
            .eq(RunApprovalRow::getRunId, run);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunApprovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getRunId() != null) {
                mappedRow.setRunId(storedRow.getRunId());
            }
            if (storedRow.getStepId() != null) {
                mappedRow.setStepId(storedRow.getStepId());
            }
            if (storedRow.getToolCallId() != null) {
                mappedRow.setToolCallId(storedRow.getToolCallId());
            }
            if (storedRow.getApproverUserId() != null) {
                mappedRow.setApproverUserId(storedRow.getApproverUserId());
            }
            if (storedRow.getRequestHash() != null) {
                mappedRow.setRequestHash(storedRow.getRequestHash());
            }
            if (storedRow.getSummaryJson() != null) {
                mappedRow.setSummaryJson(storedRow.getSummaryJson());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getDecidedAt() != null) {
                mappedRow.setDecidedAt(
                    (storedRow.getDecidedAt() == null ? null : Timestamp.from(storedRow.getDecidedAt())));
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }

    default List<RunApprovalQueryRow> forStepRunApproval(String enterprise, String run, String step) {
        var criteria = new LambdaQueryWrapper<RunApprovalRow>().eq(RunApprovalRow::getEnterpriseId, enterprise)
            .eq(RunApprovalRow::getRunId, run).eq(RunApprovalRow::getStepId, step);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunApprovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getRunId() != null) {
                mappedRow.setRunId(storedRow.getRunId());
            }
            if (storedRow.getStepId() != null) {
                mappedRow.setStepId(storedRow.getStepId());
            }
            if (storedRow.getToolCallId() != null) {
                mappedRow.setToolCallId(storedRow.getToolCallId());
            }
            if (storedRow.getApproverUserId() != null) {
                mappedRow.setApproverUserId(storedRow.getApproverUserId());
            }
            if (storedRow.getRequestHash() != null) {
                mappedRow.setRequestHash(storedRow.getRequestHash());
            }
            if (storedRow.getSummaryJson() != null) {
                mappedRow.setSummaryJson(storedRow.getSummaryJson());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getDecidedAt() != null) {
                mappedRow.setDecidedAt(
                    (storedRow.getDecidedAt() == null ? null : Timestamp.from(storedRow.getDecidedAt())));
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }

    default int createRunApproval(String id, String enterprise, String run, String step, String tool, String user,
                                  String hash, String summaryJson, Timestamp expiresAt, Timestamp now) {
        var databaseRow = new RunApprovalRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setRunId(run);
        databaseRow.setStepId(step);
        databaseRow.setToolCallId(tool);
        databaseRow.setApproverUserId(user);
        databaseRow.setRequestHash(hash);
        databaseRow.setSummaryJson(summaryJson);
        databaseRow.setExpiresAt((expiresAt == null ? null : expiresAt.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int decideRunApproval(String status, String note, Timestamp now, String enterpriseId, String id,
                                  long revision) {
        return update(new LambdaUpdateWrapper<RunApprovalRow>().eq(RunApprovalRow::getEnterpriseId, enterpriseId)
            .eq(RunApprovalRow::getId, id).eq(RunApprovalRow::getStatus, "pending")
            .eq(RunApprovalRow::getRevision, revision).set(RunApprovalRow::getStatus, status)
            .set(RunApprovalRow::getDecisionNote, note).set(RunApprovalRow::getDecidedAt, now)
            .setIncrBy(RunApprovalRow::getRevision, 1).set(RunApprovalRow::getUpdatedAt, now));
    }

    default List<RunApprovalQueryRow> expiredRunApproval(Timestamp now) {
        var query = new LambdaQueryWrapper<RunApprovalRow>().select(RunApprovalRow::getEnterpriseId,
                RunApprovalRow::getRunId)
            .eq(RunApprovalRow::getStatus, "pending").le(RunApprovalRow::getExpiresAt, now)
            .groupBy(RunApprovalRow::getEnterpriseId, RunApprovalRow::getRunId)
            .orderByAsc(RunApprovalRow::getEnterpriseId, RunApprovalRow::getRunId);
        return selectPage(new Page<RunApprovalRow>(1, 50, false), query).getRecords().stream().map(row -> {
            var result = new RunApprovalQueryRow();
            result.setQueryValue1(row.getEnterpriseId());
            result.setRunId(row.getRunId());
            return result;
        }).toList();
    }

    default int removeRunApproval(String enterpriseId, String id) {
        return delete(new LambdaQueryWrapper<RunApprovalRow>().eq(RunApprovalRow::getEnterpriseId, enterpriseId)
            .eq(RunApprovalRow::getRunId, id));
    }
}
