package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ExecutionLeaseRenewed;
import com.stonewu.agenteam.model.execution.entity.ExecutionLimits;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.model.execution.response.RunView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.schedule.ScheduleRecheckService;
import com.stonewu.agenteam.service.schedule.ScheduleRunStateService;
import com.stonewu.agenteam.service.tool.ToolQueryPolicy;
import com.stonewu.agenteam.service.usage.QuotaReservationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 开始、停止与终态统一提交；取消与晚到完成回调不能改写已经结束的任务。
 */
@Service
public class RunLifecycleService {
    private final EnterpriseMapper enterprises;
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final RunJobMapper jobs;
    private final ExecutionMessageMapper messages;
    private final ExecutionEventMapper events;
    private final ExecutionAccessService access;
    private final EnterpriseAuthorizationService authorization;
    private final QuotaReservationService quotas;
    private final Clock clock;
    private final RunStepMapper steps;
    private final RunActivityMapper activity;
    private final ToolCallMapper toolCalls;
    private final RunApprovalMapper approvals;
    private final RunCheckpointMapper checkpoints;
    private final ToolQueryPolicy toolQueries;
    private final ScheduleRunStateService scheduleStates;
    private final ScheduleRecheckService scheduleAccess;
    private final RunAttemptRetryService retries;
    private final ExecutionNotificationService notifications;
    private final ApplicationEventPublisher leaseNotifications;
    private final LivePartialOutputRecovery partialOutput;

    public RunLifecycleService(EnterpriseMapper enterprises, ConversationMapper conversations, RunMapper runs,
                               RunJobMapper jobs,
                               ExecutionMessageMapper messages, ExecutionEventMapper events,
                               ExecutionAccessService access,
                               EnterpriseAuthorizationService authorization, QuotaReservationService quotas,
                               Clock clock, RunStepMapper steps, RunActivityMapper activity,
                               ToolCallMapper toolCalls, RunApprovalMapper approvals, RunCheckpointMapper checkpoints,
                               ToolQueryPolicy toolQueries,
                               ScheduleRunStateService scheduleStates, ScheduleRecheckService scheduleAccess,
                               RunAttemptRetryService retries, ExecutionNotificationService notifications,
                               ApplicationEventPublisher leaseNotifications, LivePartialOutputRecovery partialOutput) {
        this.enterprises = enterprises;
        this.conversations = conversations;
        this.runs = runs;
        this.jobs = jobs;
        this.messages = messages;
        this.events = events;
        this.access = access;
        this.authorization = authorization;
        this.quotas = quotas;
        this.clock = clock;
        this.steps = steps;
        this.activity = activity;
        this.toolCalls = toolCalls;
        this.approvals = approvals;
        this.checkpoints = checkpoints;
        this.toolQueries = toolQueries;
        this.scheduleStates = scheduleStates;
        this.scheduleAccess = scheduleAccess;
        this.retries = retries;
        this.notifications = notifications;
        this.leaseNotifications = leaseNotifications;
        this.partialOutput = partialOutput;
    }

    @Transactional
    public Optional<JobLease> claim(String owner) {
        return jobs.claim(owner, clock.instant(), clock.instant().plusSeconds(30));
    }

    @Transactional
    public Optional<RunRecord> start(JobLease lease) {
        var run = locked(lease.enterpriseId(), lease.runId());
        if (run.terminal() || !jobs.valid(lease, clock.instant(), true)) {
            return Optional.empty();
        }
        if (!run.status().equals("queued")) {
            return Optional.empty();
        }
        if (!activity.get(run).queuedAt().plus(Duration.ofMinutes(5)).isAfter(clock.instant())) {
            finishLocked(run, "failed", "EXECUTION_QUEUE_TIMEOUT", "等待执行的时间过长，请重新发起任务。");
            return Optional.empty();
        }
        access.actor(run);
        runs.start(run, lease.version(), clock.instant());
        var started = runs.find(run.enterpriseId(), run.id(), false).orElseThrow();
        scheduleStates.changed(started);
        events.append(started, "run.started", RunView.from(started, false), clock.instant());
        return runs.find(run.enterpriseId(), run.id(), false);
    }

    @Transactional
    public void beforeExternalStep(JobLease lease) {
        var run = locked(lease.enterpriseId(), lease.runId());
        requireLease(run, lease);
        if (!run.status().equals("running") || run.cancelRequestedAt() != null) {
            throw new ExecutionStoppedException();
        }
        var actor = access.actor(run);
        remaining(run);
        quotas.consume(actor, run.id());
    }

    @Transactional
    public void reserveSteps(JobLease lease, List<String> tools, int modelCost, String phase) {
        var run = locked(lease.enterpriseId(), lease.runId());
        requireLease(run, lease);
        remaining(run);
        int used = activity.reserve(run, tools.stream().map(key -> run.currentAttemptNo() + ":" + key).toList(),
            modelCost, phase);
        int maxSteps = ExecutionLimits.from(run).maxSteps();
        if (maxSteps > 0 && used > maxSteps) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_STEP_LIMIT",
                "本次任务已达到允许的步骤上限，请缩小任务范围后重试。");
        }
    }

    public Duration remaining(RunRecord run) {
        int timeoutSeconds = ExecutionLimits.from(run).timeoutSeconds();
        if (timeoutSeconds == 0) {
            return Duration.ZERO;
        }
        long left = timeoutSeconds * 1000L - activity.get(run).elapsed(clock.instant());
        if (left <= 0) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "EXECUTION_TIMEOUT",
                "本次执行已超时，已保存的内容仍可查看。");
        }
        return Duration.ofMillis(left);
    }

    public boolean renew(JobLease lease) {
        return jobs.renew(lease, clock.instant(), clock.instant().plusSeconds(30));
    }

    public boolean shouldStop(JobLease lease) {
        return runs.find(lease.enterpriseId(), lease.runId(), false)
            .map(run -> run.terminal() || run.status().equals("cancelling") || run.leaseVersion() != lease.version())
            .orElse(true);
    }

    @Transactional
    public boolean recheckAndRenew(JobLease lease) {
        var run = locked(lease.enterpriseId(), lease.runId());
        if (run.terminal() || run.leaseVersion() != lease.version() || !jobs.valid(lease, clock.instant(), true)) {
            return false;
        }
        if (run.status().equals("cancelling")) {
            return false;
        }
        try {
            access.actor(run);
        } catch (ResponseStatusException revoked) {
            cancelForAccessLocked(run);
            return false;
        }
        var until = clock.instant().plusSeconds(30);
        boolean renewed = jobs.renew(lease, clock.instant(), until);
        if (renewed) {
            leaseNotifications.publishEvent(new ExecutionLeaseRenewed(run, lease, until));
        }
        return renewed;
    }

    @Transactional
    public RunView cancel(AuthContext actor, String id) {
        var found = runs.find(actor.enterpriseId(), id, false).filter(run -> run.userId().equals(actor.userId()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        authorization.lockAndRequire(actor, ExecutionAccessService.permission(found));
        var run = locked(actor.enterpriseId(), found.id());
        cancelLocked(run);
        return RunView.from(runs.find(run.enterpriseId(), run.id(), false).orElseThrow(), false);
    }

    @Transactional
    public void finish(JobLease lease, String status, String code, String message) {
        var run = locked(lease.enterpriseId(), lease.runId());
        if (run.terminal() || run.leaseVersion() != lease.version() || !jobs.valid(lease, clock.instant(), true)) {
            return;
        }
        String outcome = run.status().equals("cancelling") && !"TOOL_RESULT_UNKNOWN".equals(
            code) ? "cancelled" : status;
        finishLocked(run, outcome, outcome.equals("cancelled") ? null : code,
            outcome.equals("cancelled") ? null : message);
    }

    /**
     * 在领取后的校验失败时结束排队记录，不要求已经进入 running。
     */
    @Transactional
    public void rejectClaim(JobLease lease, String code, String message) {
        var run = locked(lease.enterpriseId(), lease.runId());
        if (run.terminal() || !jobs.valid(lease, clock.instant(), true)) {
            return;
        }
        boolean cancelled = run.status().equals("cancelling") && !"TOOL_RESULT_UNKNOWN".equals(code);
        finishLocked(run, cancelled ? "cancelled" : "failed", cancelled ? null : code, cancelled ? null : message);
    }

    public List<JobLease> expired() {
        return jobs.expired(clock.instant(), 50);
    }

    public List<JobLease> queueTimeouts() {
        return jobs.queuedBefore(clock.instant().minus(Duration.ofMinutes(5)), 50);
    }

    public List<RunApprovalMapper.ExpiredRun> expiredApprovals() {
        return approvals.expired(clock.instant());
    }

    @Transactional
    public void expireApproval(RunApprovalMapper.ExpiredRun candidate) {
        var run = locked(candidate.enterpriseId(), candidate.runId());
        if (!run.status().equals("waiting_approval")) {
            return;
        }
        if (approvals.forRun(run.enterpriseId(), run.id()).stream()
            .anyMatch(value -> value.status().equals("pending") && !value.expiresAt().isAfter(clock.instant()))) {
            finishLocked(run, "failed", "APPROVAL_EXPIRED", "本次操作确认已过期，未发送请求。");
        }
    }

    @Transactional
    public void recover(JobLease lease) {
        var run = locked(lease.enterpriseId(), lease.runId());
        if (run.terminal() || !jobs.expiredAndLocked(lease, clock.instant())) {
            return;
        }
        if (run.status().equals("queued") && activity.get(run).queuedAt().plus(Duration.ofMinutes(5))
            .isAfter(clock.instant())) {
            jobs.requeue(lease, clock.instant());
        } else if (run.status().equals("running") && List.of("between_steps", "tools")
            .contains(activity.get(run).phase())
            && checkpoints.find(run.enterpriseId(), run.id()).isPresent()
            && toolCalls.forRun(run.enterpriseId(), run.id()).stream()
            .noneMatch(call -> call.status().equals("unknown") ||
                (!call.readOnly() && call.status()
                    .equals("running") && call.submittedAt() != null && !toolQueries.canQuery(run, call)))) {
            try {
                access.actor(run);
                remaining(run);
            } catch (ResponseStatusException unavailable) {
                finishLocked(run, "failed", "EXECUTION_ACCESS_REVOKED", "当前任务已不能继续执行。");
                return;
            }
            toolCalls.recoverUnsentAndReads(run, clock.instant());
            activity.queue(run, clock.instant());
            jobs.requeue(lease, clock.instant());
            scheduleStates.changed(runs.find(run.enterpriseId(), run.id(), false).orElseThrow());
            events.append(run, "run.resumed",
                RunView.from(runs.find(run.enterpriseId(), run.id(), false).orElseThrow(), false), clock.instant());
        } else {
            boolean cancelled = run.status().equals("cancelling");
            finishLocked(run, cancelled ? "cancelled" : "failed", cancelled ? null : "EXECUTION_INTERRUPTED",
                cancelled ? null : "本次执行已中断，请重新执行。");
        }
    }

    @Transactional
    public void expireQueue(JobLease lease) {
        var run = locked(lease.enterpriseId(), lease.runId());
        if (run.status().equals("queued") && !activity.get(run).queuedAt().plus(Duration.ofMinutes(5))
            .isAfter(clock.instant())) {
            finishLocked(run, "failed", "EXECUTION_QUEUE_TIMEOUT", "等待执行的时间过长，请重新发起任务。");
        }
    }

    /**
     * 调用方已经验证企业管理操作；只停止实际受影响且仍未结束的执行。
     */
    @Transactional
    public void stopForUser(String enterprise, String user) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        scheduleAccess.enterprise(enterprise);
        for (var run : runs.activeForEnterprise(enterprise)) {
            if (run.userId().equals(user)) {
                cancelLocked(locked(enterprise, run.id()));
            }
        }
    }

    @Transactional
    public void stopForResource(String enterprise, String resource, String user) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        scheduleAccess.enterprise(enterprise);
        for (var run : runs.activeForEnterprise(enterprise)) {
            if (user != null && (!run.userId().equals(user) || run.mode().equals("preview"))) {
                continue;
            }
            boolean uses = run.executionConfig().path("agentId").asText().equals(resource) || run.executionConfig()
                .path("workflowId").asText().equals(resource);
            for (var dependency : run.executionConfig().path("dependencies")) {
                uses |= dependency.path("resourceId").asText().equals(resource);
            }
            if (uses) {
                var current = locked(enterprise, run.id());
                if (user == null) {
                    cancelForAccessLocked(current);
                } else {
                    cancelLocked(current);
                }
            }
        }
    }

    @Transactional
    public void stopForVersion(String enterprise, String version) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        scheduleAccess.enterprise(enterprise);
        for (var run : runs.activeForEnterprise(enterprise)) {
            boolean uses = version.equals(run.agentVersionId());
            for (var dependency : run.executionConfig().path("dependencies")) {
                uses |= version.equals(dependency.path("versionId").asText());
            }
            if (uses) {
                cancelForAccessLocked(locked(enterprise, run.id()));
            }
        }
    }

    @Transactional
    public void stopForEnterprise(String enterprise) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        scheduleAccess.enterprise(enterprise);
        for (var run : runs.activeForEnterprise(enterprise)) {
            cancelForAccessLocked(locked(enterprise, run.id()));
        }
    }

    @Transactional
    public void recheckEnterprise(String enterprise) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        scheduleAccess.enterprise(enterprise);
        for (var run : runs.activeForEnterprise(enterprise)) {
            var current = locked(enterprise, run.id());
            try {
                access.actor(current);
            } catch (ResponseStatusException denied) {
                cancelForAccessLocked(current);
            }
        }
    }

    public void requireLease(RunRecord run, JobLease lease) {
        if (run.terminal() || run.leaseVersion() != lease.version() || !jobs.valid(lease, clock.instant(), true)) {
            throw new ExecutionStoppedException();
        }
    }

    public RunRecord locked(String enterprise, String id) {
        enterprises.lockEnterprise(enterprise).orElseThrow(ResourceAuthorizationService::unavailable);
        var before = runs.find(enterprise, id, false).orElseThrow(ResourceAuthorizationService::unavailable);
        conversations.find(enterprise, before.userId(), before.conversationId(), true)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return runs.find(enterprise, id, true).orElseThrow();
    }

    private void cancelForAccessLocked(RunRecord run) {
        notifications.accessRevoked(run);
        cancelLocked(run);
    }

    private void cancelLocked(RunRecord run) {
        if (run.terminal() || run.status().equals("cancelling")) {
            return;
        }
        if (run.status().equals("queued") || run.status().equals("waiting_approval")) {
            finishLocked(run, "cancelled", null, null);
        } else {
            runs.requestCancel(run, clock.instant());
            scheduleStates.changed(runs.find(run.enterpriseId(), run.id(), false).orElseThrow());
            events.append(run, "run.cancelling",
                RunView.from(runs.find(run.enterpriseId(), run.id(), false).orElseThrow(), false), clock.instant());
        }
    }

    private void finishLocked(RunRecord run, String status, String code, String message) {
        if (!List.of("completed", "failed", "cancelled").contains(status)) {
            throw new IllegalArgumentException("执行结束状态不合法");
        }
        for (var approval : approvals.forRun(run.enterpriseId(), run.id())) {
            if (approval.status().equals("pending")) {
                approvals.decide(approval, "APPROVAL_EXPIRED".equals(code) ? "expired" : "revoked", "当前任务已经结束。",
                    clock.instant());
                events.append(run, "approval.resolved",
                    approvals.find(run.enterpriseId(), approval.id(), false).orElseThrow().view(), clock.instant());
            }
        }
        for (var call : toolCalls.forRun(run.enterpriseId(), run.id())) {
            boolean unknown = call.status().equals("unknown") || (!call.readOnly() && call.status()
                .equals("running") && call.submittedAt() != null);
            if (unknown) {
                status = "failed";
                code = "TOOL_RESULT_UNKNOWN";
                message = "无法确认操作结果，请先到目标系统核对。";
                if (!call.status().equals("unknown")) {
                    toolCalls.finish(call, "unknown", null, null, code, message, clock.instant());
                }
            } else if (List.of("prepared", "waiting_approval", "running").contains(call.status())) {
                toolCalls.finish(call, "cancelled", null, null, "TOOL_CANCELLED", "本次调用没有完成，未继续发送。",
                    clock.instant());
            }
        }
        final String outcome = status;
        for (var step : steps.list(run.enterpriseId(), run.id())) {
            if (!List.of("pending", "running", "waiting_approval").contains(step.status())) {
                continue;
            }
            var finished = new RunStepView(step.id(), step.parentStepId(), step.attemptId(), step.kind(), step.title(),
                step.displayOrder(), status, step.publicSummary(), step.startedAt(), clock.instant().toString(),
                step.workflow());
            steps.save(run, finished, null, clock.instant());
            events.append(run, "step.updated", finished, clock.instant());
        }
        var storedOutput = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow();
        var output = partialOutput.merge(run, storedOutput);
        var blocks = output.blocks().stream()
            .map(block -> List.of("pending", "running", "waiting_approval").contains(block.status())
                ? block.update(block.text(), outcome) : block).toList();
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            if (storedOutput.blocks().stream().noneMatch(block::equals)) {
                events.append(run, "block.updated", Map.of("messageId", output.id(), "block", blocks.get(i)),
                    clock.instant());
            }
        }
        messages.save(run.enterpriseId(), output.id(), output.content(), blocks, status, run.lastSequence(),
            clock.instant());
        if (status.equals("failed") && retries.schedule(run, code, message)) {
            return;
        }
        runs.finish(run, status, code, message, clock.instant());
        var finalRun = runs.find(run.enterpriseId(), run.id(), false).orElseThrow();
        scheduleStates.changed(finalRun);
        notifications.finished(finalRun);
        var event = events.append(finalRun, "run." + status, RunView.from(finalRun, false), clock.instant());
        messages.status(run.enterpriseId(), output.id(), status, Long.parseLong(event.sequence()), clock.instant());
        quotas.release(run.enterpriseId(), run.id());
        jobs.finish(run.enterpriseId(), run.id(), status, code, message, clock.instant());
        conversations.finish(run.enterpriseId(), run.conversationId(), run.id(), clock.instant());
    }

    public static final class ExecutionStoppedException extends RuntimeException {
        public ExecutionStoppedException() {
            super("当前执行已停止或由其他进程接管。");
        }
    }
}
