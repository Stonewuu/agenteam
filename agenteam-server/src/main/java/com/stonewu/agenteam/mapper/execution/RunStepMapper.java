package com.stonewu.agenteam.mapper.execution;


import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.entity.RunStepQueryRow;
import com.stonewu.agenteam.model.execution.entity.RunStepRow;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.model.execution.response.WorkflowStepDetails;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public record RunStepBatch(List<RunStepView> steps, boolean hasMore) {
    }

    /** 一次查询返回多个执行的步骤概要；触及总量上限时标记未读全的轮次，交由分页接口补齐。 */
    public Map<String, RunStepBatch> listByRuns(String enterprise, Collection<String> runIds, int totalLimit) {
        if (runIds == null || runIds.isEmpty() || totalLimit <= 0) {
            return Map.of();
        }
        var rows = statements.listByRuns(enterprise, runIds, totalLimit + 1);
        boolean truncated = rows.size() > totalLimit;
        String truncatedFromRun = truncated ? rows.getLast().getRunId() : null;
        if (truncated) {
            rows = rows.subList(0, totalLimit);
        }
        Map<String, List<RunStepView>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            grouped.computeIfAbsent(row.getRunId(), ignored -> new ArrayList<>()).add(mapRow(row));
        }
        Map<String, RunStepBatch> result = new LinkedHashMap<>();
        for (String id : runIds) {
            boolean incomplete = truncated && (!grouped.containsKey(id) || id.equals(truncatedFromRun));
            result.put(id, new RunStepBatch(incomplete ? List.of() : grouped.getOrDefault(id, List.of()), incomplete));
        }
        return result;
    }

    private RunStepView map(RunStepQueryRow row) {
        var start = instant(row.getStartedAt());
        var end = instant(row.getFinishedAt());
        return new RunStepView(row.getId(), row.getParentStepId(), row.getAttemptId(), row.getKind(),
            row.getTitle(), row.getDisplayOrder(), row.getStatus(), row.getPublicSummary(),
            start == null ? null : start.toString(), end == null ? null : end.toString(),
            json.read(row.getWorkflowJson(), WorkflowStepDetails.class));
    }

    private RunStepView mapRow(RunStepRow row) {
        var start = row.getStartedAt();
        var end = row.getFinishedAt();
        return new RunStepView(row.getId(), row.getParentStepId(), row.getAttemptId(), row.getKind(),
            row.getTitle(), row.getDisplayOrder() == null ? 0 : Math.toIntExact(row.getDisplayOrder()),
            row.getStatus(), row.getPublicSummary(),
            start == null ? null : start.toString(), end == null ? null : end.toString(),
            json.read(row.getWorkflowJson(), WorkflowStepDetails.class));
    }
}
