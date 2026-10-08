package com.stonewu.agenteam.mapper.schedule;

import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunAccepted;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleOccurrenceQueryRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduleOccurrenceRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 发生记录只在已验证本人计划后读取，实际尝试数来自对应执行记录。
 */
@Repository
public class ScheduleOccurrenceMapper {
    private final ScheduleOccurrenceSqlMapper statements;

    public ScheduleOccurrenceMapper(ScheduleOccurrenceSqlMapper statements) {
        this.statements = statements;
    }

    public Optional<ScheduleOccurrenceRecord> find(String enterprise, String schedule, String key) {
        return statements.findRunAttempt(enterprise, schedule, key).stream().map(this::map).findFirst();
    }

    public Optional<ScheduleOccurrenceRecord> forRun(String enterprise, String run) {
        return statements.forRunRunAttempt(enterprise, run).stream().map(this::map).findFirst();
    }

    public Optional<ScheduleOccurrenceRecord> active(String enterprise, String schedule, String id) {
        if (id == null) {
            return Optional.empty();
        }
        return statements.activeRunAttempt(enterprise, schedule, id).stream().map(this::map).findFirst();
    }

    public void create(String id, String enterprise, String schedule, String kind, String key, Instant time,
                       RunAccepted run, String status, String reason, Instant now) {
        statements.createScheduledOccurrence(id, enterprise, schedule, kind, key, timestamp(time),
            run == null ? null : run.runId(), run == null ? null : run.conversationId(), status, reason,
            run == null ? timestamp(now) : null, timestamp(now));
    }

    public void state(RunRecord run, Instant now) {
        // 停止请求完成前仍占用计划，避免外部请求结束前产生第二次执行。
        String status = run.status().equals("cancelling") ? "running" : run.status();
        statements.stateScheduledOccurrence(status, run.errorCode(), timestamp(run.startedAt()),
            timestamp(run.finishedAt()), timestamp(now), run.enterpriseId(), run.id());
    }

    public Optional<ScheduleOccurrenceRecord> latest(String enterprise, String schedule) {
        return statements.latestRunAttempt(enterprise, schedule).stream().map(this::map).findFirst();
    }

    public List<ScheduleOccurrenceRecord> list(String enterprise, String schedule, PagePosition cursor, int limit) {
        return statements.listOccurrences(enterprise, schedule, cursor, limit + 1).stream().map(this::map).toList();
    }

    public List<ScheduleOccurrenceRecord> summaries(String enterprise, List<String> schedules) {
        return schedules.isEmpty() ? List.of() : statements.summaries(enterprise, schedules).stream().map(this::map).toList();
    }

    private ScheduleOccurrenceRecord map(ScheduleOccurrenceQueryRow rows) {
        return new ScheduleOccurrenceRecord(rows.getId(), rows.getEnterpriseId(), rows.getScheduleId(),
            rows.getTriggerKind(),
            rows.getOccurrenceKey(), instant(rows.getScheduledFor()), rows.getRunId(), rows.getConversationId(),
            rows.getStatus(),
            rows.getReasonCode(), rows.getErrorMessage(), rows.getAttemptCount(), instant(rows.getStartedAt()),
            instant(rows.getFinishedAt()),
            instant(rows.getCreatedAt()), instant(rows.getUpdatedAt()), rows.getActionType(), rows.getActionSchemaVersion(),
            rows.getScheduleRevision(), rows.getActionSnapshotJson(), rows.getActionResultJson(), rows.getSnapshotOrigin(),
            rows.getActionJobId(), rows.getErrorSummary());
    }
}
