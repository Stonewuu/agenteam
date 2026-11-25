package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.ExecutionLimits;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunView;
import com.stonewu.agenteam.service.schedule.ScheduleRunStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 新尝试共用原输入并保留失败回复。普通对话从模型调用前的状态继续，定时任务按计划重新执行。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RunAttemptRetryService {
    private static final Set<String> TEMPORARY = Set.of("MODEL_TEMPORARY_FAILURE", "REMOTE_TIMEOUT",
        "REMOTE_CONNECTION_FAILED", "REMOTE_SERVER_UNAVAILABLE");
    private final RunMapper runs;
    private final RunAttemptMapper attempts;
    private final ExecutionMessageMapper messages;
    private final ConversationChangePublisher events;
    private final RunJobMapper jobs;
    private final RunCheckpointMapper checkpoints;
    private final RunApprovalMapper approvals;
    private final ToolCallMapper calls;
    private final RunActivityMapper activity;
    private final ScheduleRunStateService schedules;
    private final Clock clock;

    public RunAttemptRetryService(RunMapper runs, RunAttemptMapper attempts, ExecutionMessageMapper messages,
                                  ConversationChangePublisher events, RunJobMapper jobs,
                                  RunCheckpointMapper checkpoints, RunApprovalMapper approvals, ToolCallMapper calls,
                                  RunActivityMapper activity, ScheduleRunStateService schedules, Clock clock) {
        this.runs = runs;
        this.attempts = attempts;
        this.messages = messages;
        this.events = events;
        this.jobs = jobs;
        this.checkpoints = checkpoints;
        this.approvals = approvals;
        this.calls = calls;
        this.activity = activity;
        this.schedules = schedules;
        this.clock = clock;
    }

    public boolean schedule(RunRecord run, String code, String message) {
        boolean conversation = ConversationRunQueue.supported(run);
        if ((!conversation && !List.of("scheduled", "manual_schedule").contains(run.mode()))
            || !run.status().equals("running") || run.cancelRequestedAt() != null
            || run.currentAttemptNo() >= run.maxAttempts() || code == null || !TEMPORARY.contains(code)) {
            return false;
        }
        var used = activity.get(run);
        if (conversation && (!code.equals("MODEL_TEMPORARY_FAILURE") || !"model".equals(used.phase())
            || checkpoints.find(run.enterpriseId(), run.id())
                .filter(saved -> saved.leaseVersion() == run.leaseVersion()).isEmpty())) {
            return false;
        }
        if (approvals.forRun(run.enterpriseId(), run.id()).stream()
            .anyMatch(value -> List.of("rejected", "expired", "revoked").contains(value.status()))) {
            return false;
        }
        var tools = calls.forRun(run.enterpriseId(), run.id());
        if (tools.stream()
            .anyMatch(value -> value.status().equals("unknown") || !conversation && !value.readOnly() && value.submittedAt() != null)) {
            return false;
        }
        if (!code.equals("MODEL_TEMPORARY_FAILURE") && tools.stream().noneMatch(
            value -> value.readOnly() && value.status().equals("failed") && code.equals(value.errorCode()))) {
            return false;
        }
        var limits = ExecutionLimits.from(run);
        if ((limits.maxSteps() > 0 && used.usedSteps() >= limits.maxSteps())
            || (limits.timeoutSeconds() > 0 && used.elapsed(clock.instant()) >= limits.timeoutSeconds() * 1000L)) {
            return false;
        }
        var now = clock.instant();
        long scheduleDelay = run.currentAttemptNo() == 1 ? 30 : 120;
        var available = now.plusSeconds(conversation ? 2L * run.currentAttemptNo() : scheduleDelay);
        attempts.fail(run, code, message, now);
        var previous = attempts.list(run.enterpriseId(), run.id()).stream()
            .filter(value -> value.attemptNo() == run.currentAttemptNo()).findFirst().orElseThrow();
        var failed = events.append(run, "attempt.updated", previous, now);
        messages.status(run.enterpriseId(), run.outputMessageId(), "failed", Long.parseLong(failed.sequence()), now);
        String output = UUID.randomUUID().toString();
        messages.create(run.enterpriseId(), run.conversationId(), output, "assistant", null, List.of(),
            run.currentAttemptNo() + 1, now);
        messages.associate(run.enterpriseId(), output, run.id());
        attempts.next(run, output);
        // 等待自动重试时用户可以停止任务，保留模型阶段才能随后从同一份工具结果手动继续。
        runs.retry(run, output, available, now, conversation ? used.phase() : "between_steps");
        if (!conversation) {
            checkpoints.remove(run.enterpriseId(), run.id());
        }
        jobs.retry(run.enterpriseId(), run.id(), available, now);
        var queued = runs.find(run.enterpriseId(), run.id(), false).orElseThrow();
        schedules.changed(queued);
        events.append(queued, "message.created",
            messages.find(run.enterpriseId(), run.conversationId(), output).orElseThrow(), now);
        events.append(queued, "run.resumed", RunView.from(queued, false), now);
        return true;
    }
}
