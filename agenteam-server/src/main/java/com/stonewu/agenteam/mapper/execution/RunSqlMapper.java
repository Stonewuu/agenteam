package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalQueryRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

/**
 * RunMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface RunSqlMapper extends MPJBaseMapper<AgentRunRow> {
    default List<RunQueryRow> findAgentRun(String enterprise, String id, boolean lock) {
        if (lock) {
            return findAgentRunLocked(enterprise, id, lock);
        }
        var criteria = new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterprise)
            .eq(AgentRunRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getConversationId() != null) {
                mappedRow.setConversationId(storedRow.getConversationId());
            }
            if (storedRow.getUserId() != null) {
                mappedRow.setUserId(storedRow.getUserId());
            }
            if (storedRow.getInputMessageId() != null) {
                mappedRow.setInputMessageId(storedRow.getInputMessageId());
            }
            if (storedRow.getOutputMessageId() != null) {
                mappedRow.setOutputMessageId(storedRow.getOutputMessageId());
            }
            if (storedRow.getAgentVersionId() != null) {
                mappedRow.setAgentVersionId(storedRow.getAgentVersionId());
            }
            if (storedRow.getMode() != null) {
                mappedRow.setMode(storedRow.getMode());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getExecutionConfigJson() != null) {
                mappedRow.setExecutionConfigJson(storedRow.getExecutionConfigJson());
            }
            if (storedRow.getCurrentAttemptNo() != null) {
                mappedRow.setCurrentAttemptNo(storedRow.getCurrentAttemptNo());
            }
            if (storedRow.getMaxAttempts() != null) {
                mappedRow.setMaxAttempts(storedRow.getMaxAttempts());
            }
            if (storedRow.getLeaseVersion() != null) {
                mappedRow.setLeaseVersion(storedRow.getLeaseVersion());
            }
            if (storedRow.getHasStepErrors() != null) {
                mappedRow.setHasStepErrors((storedRow.getHasStepErrors() != null && storedRow.getHasStepErrors() != 0));
            }
            if (storedRow.getLastSequence() != null) {
                mappedRow.setLastSequence(storedRow.getLastSequence());
            }
            if (storedRow.getStartedAt() != null) {
                mappedRow.setStartedAt(
                    (storedRow.getStartedAt() == null ? null : Timestamp.from(storedRow.getStartedAt())));
            }
            if (storedRow.getFinishedAt() != null) {
                mappedRow.setFinishedAt(
                    (storedRow.getFinishedAt() == null ? null : Timestamp.from(storedRow.getFinishedAt())));
            }
            if (storedRow.getCancelRequestedAt() != null) {
                mappedRow.setCancelRequestedAt((storedRow.getCancelRequestedAt() == null ? null : Timestamp.from(
                    storedRow.getCancelRequestedAt())));
            }
            if (storedRow.getNextAttemptAt() != null) {
                mappedRow.setNextAttemptAt(
                    (storedRow.getNextAttemptAt() == null ? null : Timestamp.from(storedRow.getNextAttemptAt())));
            }
            if (storedRow.getErrorCode() != null) {
                mappedRow.setErrorCode(storedRow.getErrorCode());
            }
            if (storedRow.getErrorMessage() != null) {
                mappedRow.setErrorMessage(storedRow.getErrorMessage());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<RunQueryRow> findAgentRunLocked(@Param("enterprise") String enterprise, @Param("id") String id,
                                         @Param("lock") boolean lock);

    default int createAgentRun(String id, String enterprise, String user, String conversation, String input,
                               String output, String version, String mode, String executionConfigJson, int maxAttempts,
                               Timestamp now) {
        var databaseRow = new AgentRunRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setUserId(user);
        databaseRow.setConversationId(conversation);
        databaseRow.setInputMessageId(input);
        databaseRow.setOutputMessageId(output);
        databaseRow.setAgentVersionId(version);
        databaseRow.setMode(mode);
        databaseRow.setExecutionConfigJson(executionConfigJson);
        databaseRow.setMaxAttempts(maxAttempts);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }


    default List<Integer> activeCountAgentRun(String enterprise) {
        return List.of(Math.toIntExact(selectCount(
            new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterprise)
                .in(AgentRunRow::getStatus, Arrays.asList("queued", "running", "waiting_approval", "cancelling")))));
    }

    default List<Integer> countUserActiveRuns(String enterprise, String user) {
        return List.of(Math.toIntExact(selectCount(
            new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterprise)
                .in(AgentRunRow::getStatus, Arrays.asList("queued", "running", "waiting_approval", "cancelling"))
                .eq(AgentRunRow::getUserId, user))));
    }

    int startAgentRun(@Param("lease") long lease, @Param("now") Timestamp now,
                      @Param("enterpriseId") String enterpriseId, @Param("id") String id);

    int startRunAttempt(@Param("now") Timestamp now, @Param("enterpriseId") String enterpriseId, @Param("id") String id,
                        @Param("currentAttemptNo") int currentAttemptNo);

    default int updateSequenceAgentRun(long sequence, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterprise)
            .eq(AgentRunRow::getId, id).set(AgentRunRow::getLastSequence, sequence));
    }

    int retryAgentRun(@Param("output") String output, @Param("available") Timestamp available,
                      @Param("now") Timestamp now, @Param("enterpriseId") String enterpriseId, @Param("id") String id,
                      @Param("currentAttemptNo") int currentAttemptNo);

    default int requestCancelAgentRun(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterpriseId)
            .eq(AgentRunRow::getId, id).in(AgentRunRow::getStatus, Arrays.asList("running", "waiting_approval"))
            .set(AgentRunRow::getStatus, "cancelling").set(AgentRunRow::getCancelRequestedAt, now)
            .set(AgentRunRow::getUpdatedAt, now));
    }

    int finishAgentRun(@Param("status") String status, @Param("code") String code, @Param("message") String message,
                       @Param("now") Timestamp now, @Param("enterpriseId") String enterpriseId, @Param("id") String id);


    default List<RunQueryRow> activeForEnterpriseAgentRun(String enterprise) {
        var criteria = new LambdaQueryWrapper<AgentRunRow>().orderByAsc(AgentRunRow::getConversationId)
            .orderByAsc(AgentRunRow::getId).eq(AgentRunRow::getEnterpriseId, enterprise)
            .isNotNull(AgentRunRow::getActiveConversationKey);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getConversationId() != null) {
                mappedRow.setConversationId(storedRow.getConversationId());
            }
            if (storedRow.getUserId() != null) {
                mappedRow.setUserId(storedRow.getUserId());
            }
            if (storedRow.getInputMessageId() != null) {
                mappedRow.setInputMessageId(storedRow.getInputMessageId());
            }
            if (storedRow.getOutputMessageId() != null) {
                mappedRow.setOutputMessageId(storedRow.getOutputMessageId());
            }
            if (storedRow.getAgentVersionId() != null) {
                mappedRow.setAgentVersionId(storedRow.getAgentVersionId());
            }
            if (storedRow.getMode() != null) {
                mappedRow.setMode(storedRow.getMode());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getExecutionConfigJson() != null) {
                mappedRow.setExecutionConfigJson(storedRow.getExecutionConfigJson());
            }
            if (storedRow.getCurrentAttemptNo() != null) {
                mappedRow.setCurrentAttemptNo(storedRow.getCurrentAttemptNo());
            }
            if (storedRow.getMaxAttempts() != null) {
                mappedRow.setMaxAttempts(storedRow.getMaxAttempts());
            }
            if (storedRow.getLeaseVersion() != null) {
                mappedRow.setLeaseVersion(storedRow.getLeaseVersion());
            }
            if (storedRow.getHasStepErrors() != null) {
                mappedRow.setHasStepErrors((storedRow.getHasStepErrors() != null && storedRow.getHasStepErrors() != 0));
            }
            if (storedRow.getLastSequence() != null) {
                mappedRow.setLastSequence(storedRow.getLastSequence());
            }
            if (storedRow.getStartedAt() != null) {
                mappedRow.setStartedAt(
                    (storedRow.getStartedAt() == null ? null : Timestamp.from(storedRow.getStartedAt())));
            }
            if (storedRow.getFinishedAt() != null) {
                mappedRow.setFinishedAt(
                    (storedRow.getFinishedAt() == null ? null : Timestamp.from(storedRow.getFinishedAt())));
            }
            if (storedRow.getCancelRequestedAt() != null) {
                mappedRow.setCancelRequestedAt((storedRow.getCancelRequestedAt() == null ? null : Timestamp.from(
                    storedRow.getCancelRequestedAt())));
            }
            if (storedRow.getNextAttemptAt() != null) {
                mappedRow.setNextAttemptAt(
                    (storedRow.getNextAttemptAt() == null ? null : Timestamp.from(storedRow.getNextAttemptAt())));
            }
            if (storedRow.getErrorCode() != null) {
                mappedRow.setErrorCode(storedRow.getErrorCode());
            }
            if (storedRow.getErrorMessage() != null) {
                mappedRow.setErrorMessage(storedRow.getErrorMessage());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    default List<RunQueryRow> forConversationAgentRun(String enterprise, String conversation) {
        var criteria = new LambdaQueryWrapper<AgentRunRow>().orderByAsc(AgentRunRow::getId)
            .eq(AgentRunRow::getEnterpriseId, enterprise).eq(AgentRunRow::getConversationId, conversation);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getConversationId() != null) {
                mappedRow.setConversationId(storedRow.getConversationId());
            }
            if (storedRow.getUserId() != null) {
                mappedRow.setUserId(storedRow.getUserId());
            }
            if (storedRow.getInputMessageId() != null) {
                mappedRow.setInputMessageId(storedRow.getInputMessageId());
            }
            if (storedRow.getOutputMessageId() != null) {
                mappedRow.setOutputMessageId(storedRow.getOutputMessageId());
            }
            if (storedRow.getAgentVersionId() != null) {
                mappedRow.setAgentVersionId(storedRow.getAgentVersionId());
            }
            if (storedRow.getMode() != null) {
                mappedRow.setMode(storedRow.getMode());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getExecutionConfigJson() != null) {
                mappedRow.setExecutionConfigJson(storedRow.getExecutionConfigJson());
            }
            if (storedRow.getCurrentAttemptNo() != null) {
                mappedRow.setCurrentAttemptNo(storedRow.getCurrentAttemptNo());
            }
            if (storedRow.getMaxAttempts() != null) {
                mappedRow.setMaxAttempts(storedRow.getMaxAttempts());
            }
            if (storedRow.getLeaseVersion() != null) {
                mappedRow.setLeaseVersion(storedRow.getLeaseVersion());
            }
            if (storedRow.getHasStepErrors() != null) {
                mappedRow.setHasStepErrors((storedRow.getHasStepErrors() != null && storedRow.getHasStepErrors() != 0));
            }
            if (storedRow.getLastSequence() != null) {
                mappedRow.setLastSequence(storedRow.getLastSequence());
            }
            if (storedRow.getStartedAt() != null) {
                mappedRow.setStartedAt(
                    (storedRow.getStartedAt() == null ? null : Timestamp.from(storedRow.getStartedAt())));
            }
            if (storedRow.getFinishedAt() != null) {
                mappedRow.setFinishedAt(
                    (storedRow.getFinishedAt() == null ? null : Timestamp.from(storedRow.getFinishedAt())));
            }
            if (storedRow.getCancelRequestedAt() != null) {
                mappedRow.setCancelRequestedAt((storedRow.getCancelRequestedAt() == null ? null : Timestamp.from(
                    storedRow.getCancelRequestedAt())));
            }
            if (storedRow.getNextAttemptAt() != null) {
                mappedRow.setNextAttemptAt(
                    (storedRow.getNextAttemptAt() == null ? null : Timestamp.from(storedRow.getNextAttemptAt())));
            }
            if (storedRow.getErrorCode() != null) {
                mappedRow.setErrorCode(storedRow.getErrorCode());
            }
            if (storedRow.getErrorMessage() != null) {
                mappedRow.setErrorMessage(storedRow.getErrorMessage());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    default List<String> previousRunAgentRun(String enterprise, String conversation) {
        var criteria = new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getId)
            .orderByDesc(AgentRunRow::getCreatedAt).orderByDesc(AgentRunRow::getId)
            .eq(AgentRunRow::getEnterpriseId, enterprise).eq(AgentRunRow::getConversationId, conversation)
            .eq(AgentRunRow::getStatus, "completed");
        long pageSize = 1;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<AgentRunRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> storedRow.getId()).toList();
    }

    default List<String> latestTerminalAgentRun(String enterprise, String conversation) {
        var criteria = new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getId)
            .orderByDesc(AgentRunRow::getLastSequence).orderByDesc(AgentRunRow::getId)
            .eq(AgentRunRow::getEnterpriseId, enterprise).eq(AgentRunRow::getConversationId, conversation)
            .in(AgentRunRow::getStatus, Arrays.asList("completed", "failed", "cancelled"));
        long pageSize = 1;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<AgentRunRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> storedRow.getId()).toList();
    }

    default List<MemberRemovalQueryRow> runsAgentRun(String enterprise, String user) {
        var criteria = new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getId, AgentRunRow::getStatus,
                AgentRunRow::getCurrentAttemptNo, AgentRunRow::getLeaseVersion).orderByAsc(AgentRunRow::getId)
            .eq(AgentRunRow::getEnterpriseId, enterprise).eq(AgentRunRow::getUserId, user)
            .notIn(AgentRunRow::getStatus, Arrays.asList("completed", "failed", "cancelled"));
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new MemberRemovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getCurrentAttemptNo() != null) {
                mappedRow.setCurrentAttemptNo(storedRow.getCurrentAttemptNo());
            }
            if (storedRow.getLeaseVersion() != null) {
                mappedRow.setLeaseVersion(storedRow.getLeaseVersion());
            }
            return mappedRow;
        }).toList();
    }

    default int removeAgentRun(String enterprise, String conversation) {
        return delete(new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterprise)
            .eq(AgentRunRow::getConversationId, conversation));
    }
}
