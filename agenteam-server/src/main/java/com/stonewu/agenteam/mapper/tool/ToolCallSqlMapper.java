package com.stonewu.agenteam.mapper.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunAttemptRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallQueryRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * ToolCallMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ToolCallSqlMapper extends MPJBaseMapper<ToolCallRow> {
    boolean hasWorkspaceFiles(@Param("enterprise") String enterprise, @Param("user") String user,
                              @Param("conversation") String conversation, @Param("session") String session);

    default List<ToolCallQueryRow> forStepToolCall(String enterprise, String run, String step) {
        return selectJoinList(ToolCallQueryRow.class,
            JoinWrappers.lambda(ToolCallRow.class).selectAll(ToolCallRow.class)
                .eq(ToolCallRow::getEnterpriseId, enterprise).eq(ToolCallRow::getRunId, run)
                .eq(ToolCallRow::getStepId, step));
    }

    default List<ToolCallQueryRow> findManyToolCalls(String enterprise, List<String> ids) {
        return selectJoinList(ToolCallQueryRow.class,
            JoinWrappers.lambda(ToolCallRow.class).selectAll(ToolCallRow.class)
                .eq(ToolCallRow::getEnterpriseId, enterprise).in(ToolCallRow::getId, ids));
    }

    default List<ToolCallQueryRow> historyToolCalls(String enterprise, String user, String conversation,
                                                    List<String> runs) {
        return selectJoinList(ToolCallQueryRow.class,
            JoinWrappers.lambda(ToolCallRow.class).selectAll(ToolCallRow.class)
                .innerJoin(AgentRunRow.class, join -> join.eq(AgentRunRow::getId, ToolCallRow::getRunId)
                    .eq(AgentRunRow::getEnterpriseId, ToolCallRow::getEnterpriseId))
                .eq(ToolCallRow::getEnterpriseId, enterprise).eq(ToolCallRow::getActorUserId, user)
                .eq(AgentRunRow::getConversationId, conversation).eq(AgentRunRow::getUserId, user)
                .in(ToolCallRow::getRunId, runs).isNotNull(ToolCallRow::getStepId));
    }

    default List<ToolCallQueryRow> resultsForConversation(String enterprise, String user, String conversation,
                                                          String afterId, int limit) {
        var query = JoinWrappers.lambda(ToolCallRow.class).selectAll(ToolCallRow.class)
            .innerJoin(AgentRunRow.class, join -> join.eq(AgentRunRow::getId, ToolCallRow::getRunId)
                .eq(AgentRunRow::getEnterpriseId, ToolCallRow::getEnterpriseId))
            .eq(ToolCallRow::getEnterpriseId, enterprise).eq(ToolCallRow::getActorUserId, user)
            .eq(AgentRunRow::getConversationId, conversation).eq(AgentRunRow::getUserId, user)
            .and(group -> group.notIn(ToolCallRow::getResourceKind, List.of("agent", "workflow"))
                .or(builtin -> builtin.notIn(ToolCallRow::getToolName,
                    List.of("read_file", "grep_files", "list_files"))))
            .isNotNull(ToolCallRow::getResultRedactedJson).orderByAsc(ToolCallRow::getId);
        if (afterId != null) {
            query.gt(ToolCallRow::getId, afterId);
        }
        return selectJoinPage(new Page<ToolCallQueryRow>(1, limit, false), ToolCallQueryRow.class, query).getRecords();
    }

    default List<ToolCallQueryRow> findToolCall(String enterprise, String id, boolean lock) {
        if (lock) {
            return findToolCallLocked(enterprise, id, lock);
        }
        var criteria = new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, enterprise)
            .eq(ToolCallRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new ToolCallQueryRow();
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
            if (storedRow.getActorUserId() != null) {
                mappedRow.setActorUserId(storedRow.getActorUserId());
            }
            if (storedRow.getResourceId() != null) {
                mappedRow.setResourceId(storedRow.getResourceId());
            }
            if (storedRow.getResourceKind() != null) {
                mappedRow.setResourceKind(storedRow.getResourceKind());
            }
            if (storedRow.getResourceVersionId() != null) {
                mappedRow.setResourceVersionId(storedRow.getResourceVersionId());
            }
            if (storedRow.getDraftRevision() != null) {
                mappedRow.setDraftRevision(storedRow.getDraftRevision());
            }
            if (storedRow.getToolName() != null) {
                mappedRow.setToolName(storedRow.getToolName());
            }
            if (storedRow.getOperationId() != null) {
                mappedRow.setOperationId(storedRow.getOperationId());
            }
            if (storedRow.getPluginToolId() != null) {
                mappedRow.setPluginToolId(storedRow.getPluginToolId());
            }
            if (storedRow.getFrameworkCallId() != null) {
                mappedRow.setFrameworkCallId(storedRow.getFrameworkCallId());
            }
            if (storedRow.getFrameworkSessionId() != null) {
                mappedRow.setFrameworkSessionId(storedRow.getFrameworkSessionId());
            }
            if (storedRow.getArgumentHash() != null) {
                mappedRow.setArgumentHash(storedRow.getArgumentHash());
            }
            if (storedRow.getRequestEncryptedJson() != null) {
                mappedRow.setRequestEncryptedJson(storedRow.getRequestEncryptedJson());
            }
            if (storedRow.getResultEncryptedJson() != null) {
                mappedRow.setResultEncryptedJson(storedRow.getResultEncryptedJson());
            }
            if (storedRow.getLeaseVersion() != null) {
                mappedRow.setLeaseVersion(storedRow.getLeaseVersion());
            }
            if (storedRow.getSubmittedAt() != null) {
                mappedRow.setSubmittedAt(
                    (storedRow.getSubmittedAt() == null ? null : Timestamp.from(storedRow.getSubmittedAt())));
            }
            if (storedRow.getOperationClass() != null) {
                mappedRow.setOperationClass(storedRow.getOperationClass());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getRequestHash() != null) {
                mappedRow.setRequestHash(storedRow.getRequestHash());
            }
            if (storedRow.getRequestRedactedJson() != null) {
                mappedRow.setRequestRedactedJson(storedRow.getRequestRedactedJson());
            }
            if (storedRow.getResultRedactedJson() != null) {
                mappedRow.setResultRedactedJson(storedRow.getResultRedactedJson());
            }
            if (storedRow.getAttemptCount() != null) {
                mappedRow.setAttemptCount(storedRow.getAttemptCount());
            }
            if (storedRow.getQueryCount() != null) {
                mappedRow.setQueryCount(storedRow.getQueryCount());
            }
            if (storedRow.getLastQueryAt() != null) {
                mappedRow.setLastQueryAt(
                    (storedRow.getLastQueryAt() == null ? null : Timestamp.from(storedRow.getLastQueryAt())));
            }
            if (storedRow.getErrorCode() != null) {
                mappedRow.setErrorCode(storedRow.getErrorCode());
            }
            if (storedRow.getErrorSummary() != null) {
                mappedRow.setErrorSummary(storedRow.getErrorSummary());
            }
            if (storedRow.getStartedAt() != null) {
                mappedRow.setStartedAt(
                    (storedRow.getStartedAt() == null ? null : Timestamp.from(storedRow.getStartedAt())));
            }
            if (storedRow.getFinishedAt() != null) {
                mappedRow.setFinishedAt(
                    (storedRow.getFinishedAt() == null ? null : Timestamp.from(storedRow.getFinishedAt())));
            }
            if (storedRow.getDurationMs() != null) {
                mappedRow.setDurationMs(storedRow.getDurationMs());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<ToolCallQueryRow> findToolCallLocked(@Param("enterprise") String enterprise, @Param("id") String id,
                                              @Param("lock") boolean lock);

    default List<ToolCallQueryRow> frameworkToolCall(String enterpriseId, String id, int currentAttemptNo,
                                                     String session, String call) {
        var criteria = JoinWrappers.lambda(ToolCallRow.class).selectAll(ToolCallRow.class)
            .innerJoin(RunAttemptRow.class, on -> on.eq(RunAttemptRow::getEnterpriseId, ToolCallRow::getEnterpriseId)
                .eq(RunAttemptRow::getRunId, ToolCallRow::getRunId).eq(RunAttemptRow::getId, ToolCallRow::getAttemptId))
            .eq(ToolCallRow::getEnterpriseId, enterpriseId).eq(ToolCallRow::getRunId, id)
            .eq(RunAttemptRow::getAttemptNo, currentAttemptNo).eq(ToolCallRow::getFrameworkSessionId, session)
            .eq(ToolCallRow::getFrameworkCallId, call);
        return selectJoinList(ToolCallQueryRow.class, criteria);
    }


    default List<ToolCallQueryRow> forRunToolCall(String enterprise, String run) {
        var criteria = new LambdaQueryWrapper<ToolCallRow>().orderByAsc(ToolCallRow::getCreatedAt)
            .orderByAsc(ToolCallRow::getId).eq(ToolCallRow::getEnterpriseId, enterprise).eq(ToolCallRow::getRunId, run);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new ToolCallQueryRow();
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
            if (storedRow.getActorUserId() != null) {
                mappedRow.setActorUserId(storedRow.getActorUserId());
            }
            if (storedRow.getResourceId() != null) {
                mappedRow.setResourceId(storedRow.getResourceId());
            }
            if (storedRow.getResourceKind() != null) {
                mappedRow.setResourceKind(storedRow.getResourceKind());
            }
            if (storedRow.getResourceVersionId() != null) {
                mappedRow.setResourceVersionId(storedRow.getResourceVersionId());
            }
            if (storedRow.getDraftRevision() != null) {
                mappedRow.setDraftRevision(storedRow.getDraftRevision());
            }
            if (storedRow.getToolName() != null) {
                mappedRow.setToolName(storedRow.getToolName());
            }
            if (storedRow.getOperationId() != null) {
                mappedRow.setOperationId(storedRow.getOperationId());
            }
            if (storedRow.getPluginToolId() != null) {
                mappedRow.setPluginToolId(storedRow.getPluginToolId());
            }
            if (storedRow.getFrameworkCallId() != null) {
                mappedRow.setFrameworkCallId(storedRow.getFrameworkCallId());
            }
            if (storedRow.getFrameworkSessionId() != null) {
                mappedRow.setFrameworkSessionId(storedRow.getFrameworkSessionId());
            }
            if (storedRow.getArgumentHash() != null) {
                mappedRow.setArgumentHash(storedRow.getArgumentHash());
            }
            if (storedRow.getRequestEncryptedJson() != null) {
                mappedRow.setRequestEncryptedJson(storedRow.getRequestEncryptedJson());
            }
            if (storedRow.getResultEncryptedJson() != null) {
                mappedRow.setResultEncryptedJson(storedRow.getResultEncryptedJson());
            }
            if (storedRow.getLeaseVersion() != null) {
                mappedRow.setLeaseVersion(storedRow.getLeaseVersion());
            }
            if (storedRow.getSubmittedAt() != null) {
                mappedRow.setSubmittedAt(
                    (storedRow.getSubmittedAt() == null ? null : Timestamp.from(storedRow.getSubmittedAt())));
            }
            if (storedRow.getOperationClass() != null) {
                mappedRow.setOperationClass(storedRow.getOperationClass());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getRequestHash() != null) {
                mappedRow.setRequestHash(storedRow.getRequestHash());
            }
            if (storedRow.getRequestRedactedJson() != null) {
                mappedRow.setRequestRedactedJson(storedRow.getRequestRedactedJson());
            }
            if (storedRow.getResultRedactedJson() != null) {
                mappedRow.setResultRedactedJson(storedRow.getResultRedactedJson());
            }
            if (storedRow.getAttemptCount() != null) {
                mappedRow.setAttemptCount(storedRow.getAttemptCount());
            }
            if (storedRow.getQueryCount() != null) {
                mappedRow.setQueryCount(storedRow.getQueryCount());
            }
            if (storedRow.getLastQueryAt() != null) {
                mappedRow.setLastQueryAt(
                    (storedRow.getLastQueryAt() == null ? null : Timestamp.from(storedRow.getLastQueryAt())));
            }
            if (storedRow.getErrorCode() != null) {
                mappedRow.setErrorCode(storedRow.getErrorCode());
            }
            if (storedRow.getErrorSummary() != null) {
                mappedRow.setErrorSummary(storedRow.getErrorSummary());
            }
            if (storedRow.getStartedAt() != null) {
                mappedRow.setStartedAt(
                    (storedRow.getStartedAt() == null ? null : Timestamp.from(storedRow.getStartedAt())));
            }
            if (storedRow.getFinishedAt() != null) {
                mappedRow.setFinishedAt(
                    (storedRow.getFinishedAt() == null ? null : Timestamp.from(storedRow.getFinishedAt())));
            }
            if (storedRow.getDurationMs() != null) {
                mappedRow.setDurationMs(storedRow.getDurationMs());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<ToolCallQueryRow> forArtifactToolCall(@Param("enterprise") String enterprise, @Param("run") String run,
                                               @Param("resource") String resource, @Param("file") String file);

    List<ToolCallQueryRow> sourceResultsForContextToolCall(@Param("enterpriseId") String enterpriseId,
                                                           @Param("conversationId") String conversationId,
                                                           @Param("userId") String userId,
                                                           @Param("recentRuns") int recentRuns);

    int prepareToolCall(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("id2") String id2,
                        @Param("currentAttemptNo") int currentAttemptNo, @Param("step") String step,
                        @Param("userId") String userId, @Param("resourceId") String resourceId,
                        @Param("resourceKind") String resourceKind,
                        @Param("resourceVersionId") String resourceVersionId,
                        @Param("pluginToolId") String pluginToolId, @Param("value") String value,
                        @Param("call") String call, @Param("session") String session, @Param("value2") String value2,
                        @Param("argumentHash") String argumentHash, @Param("requestHash") String requestHash,
                        @Param("value3") String value3, @Param("value4") String value4,
                        @Param("leaseVersion") long leaseVersion, @Param("now") Timestamp now);

    List<Boolean> previouslyRejectedToolCall(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
                                             @Param("resource") String resource, @Param("version") String version,
                                             @Param("tool") String tool,
                                             @Param("name") String name, @Param("arguments") String arguments);

    default int manualDataReadToolCall(String id, String enterpriseId, String userId, String resourceId, long revision,
                                       String hash, String requestEncryptedJson, String requestRedactedJson,
                                       Timestamp now) {
        var databaseRow = new ToolCallRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setActorUserId(userId);
        databaseRow.setResourceId(resourceId);
        databaseRow.setResourceKind("data");
        databaseRow.setDraftRevision(revision);
        databaseRow.setToolName("data_query");
        databaseRow.setOperationId(id);
        databaseRow.setOperationClass("read");
        databaseRow.setStatus("running");
        databaseRow.setArgumentHash(hash);
        databaseRow.setRequestHash(hash);
        databaseRow.setRequestEncryptedJson(requestEncryptedJson);
        databaseRow.setRequestRedactedJson(requestRedactedJson);
        databaseRow.setStartedAt((now == null ? null : now.toInstant()));
        databaseRow.setSubmittedAt((now == null ? null : now.toInstant()));
        databaseRow.setAttemptCount(1);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    int failAbandonedManualReadsToolCall(@Param("now") Timestamp now, @Param("before") Timestamp before);

    default int waitingToolCall(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, enterpriseId)
            .eq(ToolCallRow::getId, id).eq(ToolCallRow::getStatus, "prepared")
            .set(ToolCallRow::getStatus, "waiting_approval").set(ToolCallRow::getUpdatedAt, now));
    }

    default int retryReadToolCall(long lease, Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, enterpriseId)
            .eq(ToolCallRow::getId, id).eq(ToolCallRow::getOperationClass, "read").eq(ToolCallRow::getStatus, "failed")
            .set(ToolCallRow::getStatus, "prepared").set(ToolCallRow::getResultEncryptedJson, null)
            .set(ToolCallRow::getResultRedactedJson, null).set(ToolCallRow::getErrorCode, null)
            .set(ToolCallRow::getErrorSummary, null).set(ToolCallRow::getFinishedAt, null)
            .set(ToolCallRow::getDurationMs, null).set(ToolCallRow::getLeaseVersion, lease)
            .set(ToolCallRow::getUpdatedAt, now));
    }

    int startToolCall(@Param("lease") long lease, @Param("now") Timestamp now,
                      @Param("enterpriseId") String enterpriseId, @Param("id") String id);

    int submittedToolCall(@Param("now") Timestamp now, @Param("enterpriseId") String enterpriseId,
                          @Param("id") String id, @Param("lease") long lease);

    int finishToolCall(@Param("status") String status, @Param("value") String value, @Param("value2") String value2,
                       @Param("code") String code, @Param("error") String error, @Param("now") Timestamp now,
                       @Param("enterpriseId") String enterpriseId, @Param("id") String id,
                       @Param("leaseVersion") long leaseVersion);

    default int recoverUnsentAndReadsToolCall(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, enterpriseId)
            .eq(ToolCallRow::getRunId, id).eq(ToolCallRow::getStatus, "running").and(
                group -> group.eq(ToolCallRow::getOperationClass, "read")
                    .or(other -> other.isNull(ToolCallRow::getSubmittedAt))).set(ToolCallRow::getStatus, "prepared")
            .set(ToolCallRow::getUpdatedAt, now));
    }

    default int resumeQueryToolCall(long lease, Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, enterpriseId)
            .eq(ToolCallRow::getId, id).eq(ToolCallRow::getStatus, "running").isNotNull(ToolCallRow::getSubmittedAt)
            .lt(ToolCallRow::getLeaseVersion, lease).set(ToolCallRow::getLeaseVersion, lease)
            .set(ToolCallRow::getUpdatedAt, now));
    }

    default int queriedToolCall(Timestamp now, String enterpriseId, String id, long lease) {
        return update(new LambdaUpdateWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, enterpriseId)
            .eq(ToolCallRow::getId, id).eq(ToolCallRow::getStatus, "running").eq(ToolCallRow::getLeaseVersion, lease)
            .lt(ToolCallRow::getQueryCount, 3).setIncrBy(ToolCallRow::getQueryCount, 1)
            .set(ToolCallRow::getLastQueryAt, now).set(ToolCallRow::getUpdatedAt, now));
    }

    default int removeToolCall(String enterpriseId, String id) {
        return delete(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, enterpriseId)
            .eq(ToolCallRow::getRunId, id));
    }
}
