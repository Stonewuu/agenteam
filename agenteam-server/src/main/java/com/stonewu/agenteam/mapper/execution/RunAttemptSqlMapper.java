package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunAttemptQueryRow;
import com.stonewu.agenteam.model.execution.entity.RunAttemptRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

/**
 * RunAttemptMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface RunAttemptSqlMapper extends MPJBaseMapper<RunAttemptRow> {
    default List<RunAttemptRow> outputMessages(String enterprise, String user, String conversation,
                                               Set<String> attempts) {
        if (attempts.isEmpty()) {
            return List.of();
        }
        return selectJoinList(RunAttemptRow.class, new MPJLambdaWrapper<>(RunAttemptRow.class)
            .select(RunAttemptRow::getId, RunAttemptRow::getOutputMessageId)
            .innerJoin(AgentRunRow.class, AgentRunRow::getId, RunAttemptRow::getRunId)
            .eq(RunAttemptRow::getEnterpriseId, enterprise).eq(AgentRunRow::getEnterpriseId, enterprise)
            .eq(AgentRunRow::getUserId, user).eq(AgentRunRow::getConversationId, conversation)
            .in(RunAttemptRow::getId, attempts));
    }

    default int failRunAttempt(String code, String message, Timestamp now, String enterpriseId, String id,
                               int currentAttemptNo) {
        return update(new LambdaUpdateWrapper<RunAttemptRow>().eq(RunAttemptRow::getEnterpriseId, enterpriseId)
            .eq(RunAttemptRow::getRunId, id).eq(RunAttemptRow::getAttemptNo, currentAttemptNo)
            .set(RunAttemptRow::getStatus, "failed").set(RunAttemptRow::getErrorCode, code)
            .set(RunAttemptRow::getErrorSummary, message).set(RunAttemptRow::getFinishedAt, now));
    }

    default int nextRunAttempt(String value, String enterpriseId, String id, int attemptNo, String output) {
        var databaseRow = new RunAttemptRow();
        databaseRow.setId(value);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setRunId(id);
        databaseRow.setAttemptNo(attemptNo);
        databaseRow.setOutputMessageId(output);
        databaseRow.setStatus("queued");
        return insert(databaseRow);
    }

    default List<RunAttemptQueryRow> listRunAttempt(String enterprise, String run) {
        var criteria = new LambdaQueryWrapper<RunAttemptRow>().orderByAsc(RunAttemptRow::getAttemptNo)
            .eq(RunAttemptRow::getEnterpriseId, enterprise).eq(RunAttemptRow::getRunId, run);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunAttemptQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getAttemptNo() != null) {
                mappedRow.setAttemptNo(storedRow.getAttemptNo());
            }
            if (storedRow.getOutputMessageId() != null) {
                mappedRow.setOutputMessageId(storedRow.getOutputMessageId());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getStartedAt() != null) {
                mappedRow.setStartedAt(
                    (storedRow.getStartedAt() == null ? null : Timestamp.from(storedRow.getStartedAt())));
            }
            if (storedRow.getFinishedAt() != null) {
                mappedRow.setFinishedAt(
                    (storedRow.getFinishedAt() == null ? null : Timestamp.from(storedRow.getFinishedAt())));
            }
            if (storedRow.getErrorSummary() != null) {
                mappedRow.setErrorSummary(storedRow.getErrorSummary());
            }
            return mappedRow;
        }).toList();
    }

    default int removeRunAttempt(String enterpriseId, String id) {
        return delete(new LambdaQueryWrapper<RunAttemptRow>().eq(RunAttemptRow::getEnterpriseId, enterpriseId)
            .eq(RunAttemptRow::getRunId, id));
    }

    default int pauseRunAttempt(String status, String enterpriseId, String id, int currentAttemptNo) {
        return update(new LambdaUpdateWrapper<RunAttemptRow>().eq(RunAttemptRow::getEnterpriseId, enterpriseId)
            .eq(RunAttemptRow::getRunId, id).eq(RunAttemptRow::getAttemptNo, currentAttemptNo)
            .set(RunAttemptRow::getStatus, status));
    }

    default int queueRunAttempt(String enterpriseId, String id, int currentAttemptNo) {
        return update(new LambdaUpdateWrapper<RunAttemptRow>().eq(RunAttemptRow::getEnterpriseId, enterpriseId)
            .eq(RunAttemptRow::getRunId, id).eq(RunAttemptRow::getAttemptNo, currentAttemptNo)
            .set(RunAttemptRow::getStatus, "queued"));
    }

    default int createRunAttempt(String value, String enterprise, String id, String output) {
        var databaseRow = new RunAttemptRow();
        databaseRow.setId(value);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setRunId(id);
        databaseRow.setAttemptNo(1);
        databaseRow.setOutputMessageId(output);
        databaseRow.setStatus("queued");
        return insert(databaseRow);
    }

    default int finishRunAttempt(String status, String code, String message, Timestamp now, String enterpriseId,
                                 String id, int currentAttemptNo) {
        return update(new LambdaUpdateWrapper<RunAttemptRow>().eq(RunAttemptRow::getEnterpriseId, enterpriseId)
            .eq(RunAttemptRow::getRunId, id).eq(RunAttemptRow::getAttemptNo, currentAttemptNo)
            .set(RunAttemptRow::getStatus, status).set(RunAttemptRow::getErrorCode, code)
            .set(RunAttemptRow::getErrorSummary, message).set(RunAttemptRow::getFinishedAt, now));
    }

    default List<String> attemptIdRunAttempt(String enterpriseId, String id, int currentAttemptNo) {
        var criteria = new LambdaQueryWrapper<RunAttemptRow>().select(RunAttemptRow::getId)
            .eq(RunAttemptRow::getEnterpriseId, enterpriseId).eq(RunAttemptRow::getRunId, id)
            .eq(RunAttemptRow::getAttemptNo, currentAttemptNo);
        return selectList(criteria).stream().map(storedRow -> storedRow.getId()).toList();
    }
}
