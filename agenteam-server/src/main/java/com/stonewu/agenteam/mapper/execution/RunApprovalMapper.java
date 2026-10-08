package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunApprovalQueryRow;
import com.stonewu.agenteam.model.execution.entity.RunApprovalRecord;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunApprovalView;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 审批与工具记录位于同一事务，重复生成受数据库唯一约束限制。
 */
@Repository
public class RunApprovalMapper {
    private final RunApprovalSqlMapper statements;
    private final ResourceJson json;
    private final ObjectMapper mapper;

    public RunApprovalMapper(RunApprovalSqlMapper statements, ResourceJson json, ObjectMapper mapper) {
        this.statements = statements;
        this.json = json;
        this.mapper = mapper;
    }

    public Optional<RunApprovalRecord> find(String enterprise, String id, boolean lock) {
        return statements.findRunApproval(enterprise, id, lock).stream().map(this::map).findFirst();
    }

    public Optional<RunApprovalRecord> forTool(ToolCallRecord call) {
        return statements.forToolRunApproval(call.enterpriseId(), call.id()).stream().map(this::map).findFirst();
    }

    public List<RunApprovalRecord> forRun(String enterprise, String run) {
        return statements.forRunRunApproval(enterprise, run).stream().map(this::map).toList();
    }

    public Optional<RunApprovalRecord> forStep(String enterprise, String run, String step) {
        return statements.forStepRunApproval(enterprise, run, step).stream().map(this::map).findFirst();
    }

    public RunApprovalRecord create(ToolCallRecord call, RunApprovalView.Summary summary, Instant now) {
        return create(call.enterpriseId(), call.runId(), call.stepId(), call.id(), call.actorUserId(),
            call.requestHash(), summary, now);
    }

    public RunApprovalRecord createWorkflow(RunRecord run, String step, String hash, RunApprovalView.Summary summary,
                                            Instant now) {
        return create(run.enterpriseId(), run.id(), step, null, run.userId(), hash, summary, now);
    }

    private RunApprovalRecord create(String enterprise, String run, String step, String tool, String user, String hash,
                                     RunApprovalView.Summary summary, Instant now) {
        String id = UUID.randomUUID().toString();
        statements.createRunApproval(id, enterprise, run, step, tool, user, hash, json.write(json.tree(summary)),
            timestamp(now.plusSeconds(86400)), timestamp(now));
        return find(enterprise, id, false).orElseThrow();
    }

    public void decide(RunApprovalRecord approval, String status, String note, Instant now) {
        int changed = statements.decideRunApproval(status, note, timestamp(now), approval.enterpriseId(), approval.id(),
            approval.revision());
        if (changed != 1) {
            throw new IllegalStateException("操作确认已经处理，不能覆盖决定");
        }
    }

    public record ExpiredRun(String enterpriseId, String runId) {
    }

    public List<ExpiredRun> expired(Instant now) {
        return statements.expiredRunApproval(timestamp(now)).stream()
            .map(row -> new ExpiredRun(row.getQueryValue1(), row.getRunId())).toList();
    }

    private RunApprovalRecord map(RunApprovalQueryRow row) {
        return new RunApprovalRecord(row.getId(), row.getEnterpriseId(), row.getRunId(), row.getStepId(),
            row.getToolCallId(), row.getApproverUserId(), row.getRequestHash(),
            mapper.convertValue(json.read(row.getSummaryJson()), RunApprovalView.Summary.class), row.getStatus(),
            instant(row.getExpiresAt()), instant(row.getDecidedAt()), row.getRevision());
    }
}
