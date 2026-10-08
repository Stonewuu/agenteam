package com.stonewu.agenteam.mapper.execution;


import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 等待不计入活动时间，恢复不能重置父子任务的共同步骤限制。
 */
@Repository
public class RunActivityMapper {
    public record Activity(int usedSteps, Set<String> countedTools, long activeMillis, Instant segmentStarted,
                           String phase, Instant queuedAt) {
        public long elapsed(Instant now) {
            return activeMillis + (segmentStarted == null ? 0 : Math.max(0,
                Duration.between(segmentStarted, now).toMillis()));
        }
    }

    private final RunActivitySqlMapper statements;
    private final ResourceJson json;

    private final RunAttemptSqlMapper runAttemptSqlMapper;

    public RunActivityMapper(RunActivitySqlMapper statements, ResourceJson json,
                             RunAttemptSqlMapper runAttemptSqlMapper) {
        this.runAttemptSqlMapper = runAttemptSqlMapper;
        this.statements = statements;
        this.json = json;
    }

    public Activity get(RunRecord run) {
        return DataAccessUtils.nullableSingleResult(
            statements.getAgentRun(run.enterpriseId(), run.id()).stream().map(row -> {

                Set<String> tools = new HashSet<>();

                json.read(row.getCountedToolsJson()).forEach(value -> tools.add(value.asText()));

                return new Activity(row.getUsedSteps(), Set.copyOf(tools), row.getActiveMillis(),
                    instant(row.getActiveSegmentStartedAt()), row.getExecutionPhase(), instant(row.getQueuedAt()));

            }).toList());
    }

    public int reserve(RunRecord run, List<String> toolKeys, int modelCost, String phase) {
        var previous = get(run);
        Set<String> tools = new HashSet<>(previous.countedTools());
        tools.addAll(toolKeys);
        int used = Math.addExact(previous.usedSteps(), modelCost + tools.size() - previous.countedTools().size());
        statements.reserveAgentRun(used, json.write(json.tree(tools.stream().sorted().toList())), phase,
            run.enterpriseId(), run.id());
        return used;
    }

    public void pause(RunRecord run, String status, Instant now) {
        statements.pauseAgentRun(status, timestamp(now), run.enterpriseId(), run.id());
        runAttemptSqlMapper.pauseRunAttempt(status, run.enterpriseId(), run.id(), run.currentAttemptNo());
    }

    public void queue(RunRecord run, Instant now) {
        statements.queueAgentRun(timestamp(now), run.enterpriseId(), run.id());
        runAttemptSqlMapper.queueRunAttempt(run.enterpriseId(), run.id(), run.currentAttemptNo());
    }

    public void betweenSteps(RunRecord run) {
        phase(run, "between_steps");
    }

    public void phase(RunRecord run, String value) {
        statements.phaseAgentRun(value, run.enterpriseId(), run.id());
    }

    public void stepErrors(RunRecord run) {
        statements.stepErrorsAgentRun(run.enterpriseId(), run.id());
    }
}
