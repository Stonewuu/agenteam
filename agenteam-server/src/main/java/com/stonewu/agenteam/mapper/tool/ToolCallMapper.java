package com.stonewu.agenteam.mapper.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallQueryRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 一条框架调用只对应一个固定操作编号；真实发送和结果保存各自更新记录。
 */
@Repository
public class ToolCallMapper {
    private final ToolCallSqlMapper statements;
    private final ResourceJson json;
    private final ObjectMapper mapper;

    public ToolCallMapper(ToolCallSqlMapper statements, ResourceJson json, ObjectMapper mapper) {
        this.statements = statements;
        this.json = json;
        this.mapper = mapper;
    }

    public Optional<ToolCallRecord> find(String enterprise, String id, boolean lock) {
        return statements.findToolCall(enterprise, id, lock).stream().map(this::map).findFirst();
    }

    public Optional<ToolCallRecord> framework(RunRecord run, String session, String call) {
        return statements.frameworkToolCall(run.enterpriseId(), run.id(), run.currentAttemptNo(), session, call)
            .stream().map(this::map).findFirst();
    }

    public List<ToolCallRecord> forRun(String enterprise, String run) {
        return statements.forRunToolCall(enterprise, run).stream().map(this::map).toList();
    }

    public List<ToolCallRecord> forConversationRuns(String enterprise, String user, String conversation,
                                                    List<String> runs) {
        if (runs.isEmpty()) {
            return List.of();
        }
        return statements.historyToolCalls(enterprise, user, conversation, runs).stream().map(this::map).toList();
    }

    public boolean hasWorkspaceFiles(RunRecord run, String session) {
        return statements.hasWorkspaceFiles(run.enterpriseId(), run.userId(), run.conversationId(), session);
    }

    public boolean hasConversationWorkspace(String enterprise, String user, String conversation) {
        return statements.hasWorkspaceFiles(enterprise, user, conversation, conversation);
    }

    public List<ToolCallRecord> findMany(String enterprise, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return statements.findManyToolCalls(enterprise, ids).stream().map(this::map).toList();
    }

    public Optional<ToolCallRecord> forStep(String enterprise, String run, String step) {
        var rows = statements.forStepToolCall(enterprise, run, step);
        if (rows.size() > 1) {
            throw new IllegalStateException("同一步骤存在重复的工具调用记录");
        }
        return rows.stream().map(this::map).findFirst();
    }

    public List<ToolCallRecord> resultsForConversation(String enterprise, String user, String conversation,
                                                       String afterId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("结果文件查询数量不正确");
        }
        return statements.resultsForConversation(enterprise, user, conversation, afterId, limit).stream().map(this::map)
            .toList();
    }

    public Optional<ToolCallRecord> forArtifact(String enterprise, String run, String resource, String file) {
        return statements.forArtifactToolCall(enterprise, run, resource, file).stream().map(this::map).findFirst();
    }

    public List<ToolCallRecord> sourceResultsForContext(RunRecord run) {
        int recentRuns = 1 + Math.max(0,
            Math.min(200, run.executionConfig().at("/config/historyMessageLimit").asInt()));
        return statements.sourceResultsForContextToolCall(run.enterpriseId(), run.conversationId(), run.userId(),
            recentRuns).stream().map(this::map).toList();
    }

    public void prepare(String id, RunRecord run, String step, ExecutionToolBinding binding, String session,
                        String call,
                        String argumentHash, String requestHash, EncryptedPayload request, JsonNode redacted,
                        Instant now) {
        statements.prepareToolCall(id, run.enterpriseId(), run.id(), run.currentAttemptNo(), step, run.userId(),
            binding.resourceId(), binding.resourceKind(), binding.resourceVersionId(), binding.pluginToolId(),
            binding.definition().name(), call, session, binding.definition().operationClass(), argumentHash,
            requestHash, json.write(json.tree(request)), json.write(redacted), run.leaseVersion(), timestamp(now));
    }

    public boolean previouslyRejected(RunRecord run, ExecutionToolBinding binding, String arguments) {
        return Boolean.TRUE.equals(
            DataAccessUtils.nullableSingleResult(statements.previouslyRejectedToolCall(run.enterpriseId(), run.id(),
                binding.resourceId(), binding.resourceVersionId(), binding.pluginToolId(), binding.definition().name(),
                arguments)));
    }

    public void manualDataRead(String id, AuthContext actor, ResourceRecord resource, String hash,
                               EncryptedPayload request, JsonNode redacted, Instant now) {
        statements.manualDataReadToolCall(id, actor.enterpriseId(), actor.userId(), resource.id(), resource.revision(),
            hash, json.write(json.tree(request)), json.write(redacted), timestamp(now));
    }

    public int failAbandonedManualReads(Instant before, Instant now) {
        return statements.failAbandonedManualReadsToolCall(timestamp(now), timestamp(before));
    }

    public void waiting(ToolCallRecord call, Instant now) {
        statements.waitingToolCall(timestamp(now), call.enterpriseId(), call.id());
    }

    public void retryRead(ToolCallRecord call, long lease, Instant now) {
        int changed = statements.retryReadToolCall(lease, timestamp(now), call.enterpriseId(), call.id());
        if (changed != 1) {
            throw new IllegalStateException("本次只读请求已经不能重新执行");
        }
    }

    public void start(ToolCallRecord call, long lease, Instant now) {
        int changed = statements.startToolCall(lease, timestamp(now), call.enterpriseId(), call.id());
        if (changed != 1) {
            throw new IllegalStateException("本次工具操作已经开始或结束");
        }
    }

    public void submitted(ToolCallRecord call, long lease, Instant now) {
        int changed = statements.submittedToolCall(timestamp(now), call.enterpriseId(), call.id(), lease);
        if (changed != 1) {
            throw new IllegalStateException("本次工具操作已失去执行资格");
        }
    }

    public void finish(ToolCallRecord call, String status, EncryptedPayload result, JsonNode redacted, String code,
                       String error, Instant now) {
        statements.finishToolCall(status, result == null ? null : json.write(json.tree(result)),
            redacted == null ? null : json.write(redacted), code, error, timestamp(now), call.enterpriseId(), call.id(),
            call.leaseVersion());
    }

    public void recoverUnsentAndReads(RunRecord run, Instant now) {
        statements.recoverUnsentAndReadsToolCall(timestamp(now), run.enterpriseId(), run.id());
    }

    public void resumeQuery(ToolCallRecord call, long lease, Instant now) {
        int changed = statements.resumeQueryToolCall(lease, timestamp(now), call.enterpriseId(), call.id());
        if (changed != 1) {
            throw new IllegalStateException("工具结果查询已由其他执行处理");
        }
    }

    public void queried(ToolCallRecord call, long lease, Instant now) {
        int changed = statements.queriedToolCall(timestamp(now), call.enterpriseId(), call.id(), lease);
        if (changed != 1) {
            throw new IllegalStateException("工具结果查询已达到上限或当前执行不能继续");
        }
    }

    private ToolCallRecord map(ToolCallQueryRow row) {
        return new ToolCallRecord(row.getId(), row.getEnterpriseId(), row.getRunId(), row.getStepId(),
            row.getActorUserId(), row.getResourceId(), row.getResourceKind(), row.getResourceVersionId(),
            row.getDraftRevision(), row.getPluginToolId(), row.getToolName(),
            row.getOperationId(), row.getFrameworkCallId(), row.getFrameworkSessionId(), row.getOperationClass(),
            row.getStatus(), row.getArgumentHash(), row.getRequestHash(),
            payload(row.getRequestEncryptedJson()), payload(row.getResultEncryptedJson()),
            json.read(row.getRequestRedactedJson()), json.read(row.getResultRedactedJson()), row.getLeaseVersion(),
            row.getAttemptCount(), row.getQueryCount(), instant(row.getLastQueryAt()), instant(row.getSubmittedAt()),
            instant(row.getStartedAt()), instant(row.getFinishedAt()),
            row.getDurationMs(), row.getErrorCode(), row.getErrorSummary(), instant(row.getCreatedAt()));
    }

    private EncryptedPayload payload(String value) {
        return value == null ? null : mapper.convertValue(json.read(value), EncryptedPayload.class);
    }
}
