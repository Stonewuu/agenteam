package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.ToolConfirmation;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.WorkflowConfirmation;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunApprovalRecord;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.request.ApprovalDecisionRequest;
import com.stonewu.agenteam.model.execution.response.RunApprovalView;
import com.stonewu.agenteam.model.execution.response.RunView;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.schedule.ScheduleRunStateService;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 沿用框架暂停和继续语义，只把用户决定及执行资格交给持久事务处理。
 */
@Service
public class RunApprovalService {
    private final ScheduleRunStateService scheduleStates;
    private final RunApprovalMapper approvals;
    private final ToolCallMapper calls;
    private final RunMapper runs;
    private final RunJobMapper jobs;
    private final RunActivityMapper activity;
    private final RunLifecycleService lifecycle;
    private final ExecutionAccessService access;
    private final ExecutionToolCatalog catalog;
    private final ToolCallTransactions toolTransactions;
    private final ToolPayloadMapper payloads;
    private final ResourceJson json;
    private final RunCheckpointService checkpoints;
    private final ExecutionEventMapper events;
    private final ApprovalConversationWriter messages;
    private final ConversationQueryService conversations;
    private final EnterpriseAuthorizationService authorization;
    private final AuditEventService audit;
    private final Clock clock;
    private final RunStepMapper steps;
    private final ExecutionNotificationService notifications;

    public RunApprovalService(RunApprovalMapper approvals, ToolCallMapper calls, RunMapper runs, RunJobMapper jobs,
                              RunActivityMapper activity,
                              RunLifecycleService lifecycle, ExecutionAccessService access,
                              ExecutionToolCatalog catalog, ToolCallTransactions toolTransactions,
                              ToolPayloadMapper payloads, ResourceJson json, RunCheckpointService checkpoints,
                              ExecutionEventMapper events,
                              ApprovalConversationWriter messages, ConversationQueryService conversations,
                              EnterpriseAuthorizationService authorization, AuditEventService audit, Clock clock,
                              RunStepMapper steps,
                              ScheduleRunStateService scheduleStates, ExecutionNotificationService notifications) {
        this.approvals = approvals;
        this.calls = calls;
        this.runs = runs;
        this.jobs = jobs;
        this.activity = activity;
        this.lifecycle = lifecycle;
        this.access = access;
        this.catalog = catalog;
        this.toolTransactions = toolTransactions;
        this.payloads = payloads;
        this.json = json;
        this.checkpoints = checkpoints;
        this.events = events;
        this.messages = messages;
        this.conversations = conversations;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
        this.steps = steps;
        this.scheduleStates = scheduleStates;
        this.notifications = notifications;
    }

    @Transactional
    public void park(JobLease lease, RunCheckpointService.Prepared checkpoint, List<ToolUseBlock> pending) {
        var run = runs.find(lease.enterpriseId(), lease.runId(), false).orElseThrow();
        parkAll(lease, checkpoint, List.of(new ToolConfirmation(run.conversationId(), pending)), List.of());
    }

    @Transactional
    public void parkAll(JobLease lease, RunCheckpointService.Prepared checkpoint, List<ToolConfirmation> confirmations,
                        List<WorkflowConfirmation> workflowConfirmations) {
        var run = lifecycle.locked(lease.enterpriseId(), lease.runId());
        lifecycle.requireLease(run, lease);
        if (!run.status().equals("running") || run.cancelRequestedAt() != null) {
            throw new RunLifecycleService.ExecutionStoppedException();
        }
        access.actor(run);
        var tools = catalog.all(run);
        if (confirmations.stream().allMatch(item -> item.calls().isEmpty()) && workflowConfirmations.isEmpty()) {
            throw new IllegalStateException("没有待确认操作，不能暂停执行");
        }
        checkpoints.save(lease, checkpoint);
        for (var confirmation : confirmations) {
            for (var use : confirmation.calls()) {
                var binding = tools.get(use.getName());
                if (binding == null || binding.readOnly()) {
                    throw new IllegalStateException("待确认工具不属于本次固定能力");
                }
                var call = calls.framework(run, confirmation.sessionId(), use.getId()).orElseThrow();
                if (!call.argumentHash().equals(payloads.argumentHash(json.tree(use.getInput()))) || !binding.matches(
                    call)) {
                    throw changed();
                }
                var approval = approvals.forTool(call)
                    .orElseGet(() -> approvals.create(call, toolTransactions.summary(binding, call), clock.instant()));
                if (!approval.status().equals("pending")) {
                    throw new IllegalStateException("已经处理的操作不能重新请求确认");
                }
                calls.waiting(call, clock.instant());
                messages.save(run, approval);
                events.append(run, "approval.created", approval.view(), clock.instant());
                notifications.waiting(run, approval.id());
            }
        }
        for (var confirmation : workflowConfirmations) {
            requireWorkflowStep(run, confirmation.stepId());
            if (!workflowHash(run, confirmation.stepId(), confirmation.summary()).equals(confirmation.requestHash())) {
                throw changed();
            }
            var approval = approvals.forStep(run.enterpriseId(), run.id(), confirmation.stepId()).orElseGet(() ->
                approvals.createWorkflow(run, confirmation.stepId(), confirmation.requestHash(), confirmation.summary(),
                    clock.instant()));
            if (!approval.status().equals("pending") || !approval.requestHash().equals(confirmation.requestHash())) {
                throw resolved();
            }
            messages.save(run, approval);
            events.append(run, "approval.created", approval.view(), clock.instant());
            notifications.waiting(run, approval.id());
        }
        activity.pause(run, "waiting_approval", clock.instant());
        jobs.park(lease, clock.instant());
        scheduleStates.changed(runs.find(run.enterpriseId(), run.id(), false).orElseThrow());
        events.append(run, "run.waiting_approval",
            RunView.from(runs.find(run.enterpriseId(), run.id(), false).orElseThrow(), false), clock.instant());
    }

    public List<RunApprovalView> list(AuthContext actor, String runId) {
        var run = ownRun(actor, runId);
        conversations.readable(actor, run.conversationId());
        return approvals.forRun(actor.enterpriseId(), run.id()).stream().map(RunApprovalRecord::view).toList();
    }

    public RunApprovalRecord readable(AuthContext actor, String id) {
        var approval = approvals.find(actor.enterpriseId(), id, false)
            .filter(value -> value.approverUserId().equals(actor.userId()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        var run = ownRun(actor, approval.runId());
        conversations.readable(actor, run.conversationId());
        return approval;
    }

    public void authorizeDecision(AuthContext actor, String id) {
        var approval = readable(actor, id);
        var run = ownRun(actor, approval.runId());
        authorization.require(actor, ExecutionAccessService.permission(run));
    }

    @Transactional
    public RunApprovalView decide(AuthContext actor, String id, long revision, ApprovalDecisionRequest input) {
        var original = readable(actor, id);
        var run = lifecycle.locked(actor.enterpriseId(), original.runId());
        authorization.lockAndRequire(actor, ExecutionAccessService.permission(run));
        var approval = approvals.find(actor.enterpriseId(), original.id(), true).orElseThrow();
        if (!approval.requestHash().equals(input.requestHash())) {
            throw changed();
        }
        String outcome = input.decision().equals("approve") ? "approved" : "rejected";
        if (approval.status().equals(outcome)) {
            return approval.view();
        }
        if (!approval.status().equals("pending")) {
            throw resolved();
        }
        if (approval.revision() != revision) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "VERSION_CONFLICT",
                "确认内容已经变化，请刷新后再处理。");
        }
        if (!approval.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_EXPIRED", "本次操作确认已过期，未发送请求。");
        }
        if (!run.status().equals("waiting_approval") || run.cancelRequestedAt() != null) {
            throw resolved();
        }
        access.actor(run);
        if (approval.toolCallId() != null) {
            var call = calls.find(actor.enterpriseId(), approval.toolCallId(), true).orElseThrow();
            if (!call.requestHash().equals(approval.requestHash()) || !call.argumentHash()
                .equals(payloads.argumentHash(toolTransactions.arguments(call)))) {
                throw changed();
            }
            if (catalog.all(run).values().stream().noneMatch(binding -> binding.matches(call))) {
                throw ResourceAuthorizationService.unavailable();
            }
            if (outcome.equals("rejected")) {
                toolTransactions.reject(call);
            }
        } else {
            requireWorkflowStep(run, approval.stepId());
            if (!workflowHash(run, approval.stepId(), approval.summary()).equals(approval.requestHash())) {
                throw changed();
            }
        }
        approvals.decide(approval, outcome, input.reason(), clock.instant());
        var saved = approvals.find(actor.enterpriseId(), approval.id(), false).orElseThrow();
        messages.save(run, saved);
        events.append(run, "approval.resolved", saved.view(), clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "run.approval." + input.decision(), "run_approval", saved.id(),
            outcome.equals("approved") ? "同意本次确认内容" : "拒绝本次确认内容",
            Map.of("runId", run.id(), "stepId", approval.stepId()));
        var all = approvals.forRun(run.enterpriseId(), run.id());
        if (all.stream().noneMatch(value -> value.status().equals("pending"))) {
            activity.queue(run, clock.instant());
            jobs.resume(run.enterpriseId(), run.id(), clock.instant());
            scheduleStates.changed(runs.find(run.enterpriseId(), run.id(), false).orElseThrow());
            events.append(run, "run.resumed",
                RunView.from(runs.find(run.enterpriseId(), run.id(), false).orElseThrow(), false), clock.instant());
        }
        return saved.view();
    }

    public List<Msg> input(RunRecord run, EncryptedAgentStateStore states, UserMessage first, boolean restored) {
        return input(run, states, run.conversationId(), first, restored);
    }

    public List<Msg> input(RunRecord run, EncryptedAgentStateStore states, String session, UserMessage first,
                           boolean restored) {
        var saved = states.get(run.userId(), session, "agent_state", AgentState.class);
        if (!restored) {
            return List.of(first);
        }
        if (saved.isEmpty()) {
            throw new IllegalStateException("已保存的执行缺少框架状态");
        }
        List<ConfirmResult> decisions = new ArrayList<>();
        for (var message : saved.get().getContext()) {
            for (var block : message.getContent()) {
                if (block instanceof ToolUseBlock use && use.getState() == ToolCallState.ASKING) {
                    var call = calls.framework(run, session, use.getId()).orElseThrow();
                    var approval = approvals.forTool(call).orElseThrow();
                    if (!List.of("approved", "rejected").contains(approval.status()) || !approval.requestHash()
                        .equals(call.requestHash())) {
                        throw resolved();
                    }
                    decisions.add(
                        new ConfirmResult(approval.status().equals("approved"), toolTransactions.originalCall(call)));
                }
            }
        }
        if (decisions.isEmpty()) {
            return List.of();
        }
        return List.of(UserMessage.builder().metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, decisions)).build());
    }

    public String workflowHash(RunRecord run, String step, RunApprovalView.Summary summary) {
        return json.hash(
            json.tree(Map.of("runId", run.id(), "stepId", step, "configurationHash", json.hash(run.executionConfig()),
                "argumentsHash", json.hash(steps.input(run.enterpriseId(), run.id(), step)), "summary", summary)));
    }

    public RunApprovalRecord workflowDecision(RunRecord run, String step, String expected) {
        var found = approvals.forStep(run.enterpriseId(), run.id(), step);
        if (found.isEmpty()) {
            return null;
        }
        var approval = found.get();
        if (approval.toolCallId() != null || !approval.requestHash().equals(expected)) {
            throw changed();
        }
        if (!List.of("approved", "rejected").contains(approval.status())) {
            throw resolved();
        }
        return approval;
    }

    private void requireWorkflowStep(RunRecord run, String id) {
        var step = steps.list(run.enterpriseId(), run.id()).stream().filter(value -> value.id().equals(id)).findFirst()
            .orElseThrow();
        if (step.workflow() == null || !"approval".equals(step.workflow().nodeType())) {
            throw new IllegalStateException("流程确认必须属于真实人工确认节点");
        }
    }

    private RunRecord ownRun(AuthContext actor, String id) {
        return runs.find(actor.enterpriseId(), id, false).filter(value -> value.userId().equals(actor.userId()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    private static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "APPROVAL_PARAMETERS_CHANGED",
            "确认内容与固定操作不一致，请重新读取后核对。");
    }

    private static ApiException resolved() {
        return new ApiException(HttpStatus.CONFLICT, "APPROVAL_ALREADY_RESOLVED",
            "本次操作确认已处理或失效，不能改变决定。");
    }
}
