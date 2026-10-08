package com.stonewu.agenteam.mapper.execution;


import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.entity.RunStepQueryRow;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.model.execution.response.WorkflowStepDetails;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 步骤首次插入时固定父节点与显示顺序，后续只更新真实状态和结果。
 */
@Repository
public class RunStepMapper {
    private final RunStepSqlMapper statements;
    private final ExecutionJson json;

    private final RunAttemptSqlMapper runAttemptSqlMapper;

    public RunStepMapper(RunStepSqlMapper statements, ExecutionJson json, RunAttemptSqlMapper runAttemptSqlMapper) {
        this.runAttemptSqlMapper = runAttemptSqlMapper;
        this.statements = statements;
        this.json = json;
    }

    public String attemptId(RunRecord run) {
        return DataAccessUtils.nullableSingleResult(
            runAttemptSqlMapper.attemptIdRunAttempt(run.enterpriseId(), run.id(), run.currentAttemptNo()));
    }

    public void save(RunRecord run, RunStepView step, JsonNode result, Instant now) {
        save(run, step, null, result, now);
    }

    public void save(RunRecord run, RunStepView step, JsonNode input, JsonNode result, Instant now) {
        statements.saveRunStep(step.id(), run.enterpriseId(), run.id(), step.attemptId(), step.parentStepId(),
            step.kind(), step.title(), step.displayOrder(), step.status(), input == null ? null : json.write(input),
            result == null ? null : json.write(result), step.publicSummary(),
            step.workflow() == null ? null : json.write(step.workflow()), run.leaseVersion(),
            step.startedAt() == null ? null : timestamp(Instant.parse(step.startedAt())),
            step.finishedAt() == null ? null : timestamp(Instant.parse(step.finishedAt())), timestamp(now),
            input == null);
    }

    public List<RunStepView> list(String enterprise, String run) {
        return statements.listRunStep(enterprise, run).stream().map(this::map).toList();
    }

    public JsonNode input(String enterprise, String run, String step) {
        return json.tree(DataAccessUtils.nullableSingleResult(statements.inputRunStep(enterprise, run, step)));
    }

    public List<RunStepView> page(String enterprise, String run, int afterOrder, String afterId, int limit) {
        return statements.pageRunStep(enterprise, run, afterOrder, afterId, limit).stream().map(this::map).toList();
    }

    private RunStepView map(RunStepQueryRow row) {
        var start = instant(row.getStartedAt());
        var end = instant(row.getFinishedAt());
        return new RunStepView(row.getId(), row.getParentStepId(), row.getAttemptId(), row.getKind(),
            row.getTitle(), row.getDisplayOrder(), row.getStatus(), row.getPublicSummary(),
            start == null ? null : start.toString(), end == null ? null : end.toString(),
            json.read(row.getWorkflowJson(), WorkflowStepDetails.class));
    }
}
