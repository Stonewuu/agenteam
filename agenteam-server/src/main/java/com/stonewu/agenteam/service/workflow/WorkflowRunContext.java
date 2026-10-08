package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.agent.AgentPublicEventMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.ToolConfirmation;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.WorkflowConfirmation;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.model.workflow.entity.WorkflowInvocationState;
import com.stonewu.agenteam.model.workflow.entity.WorkflowRunState;
import com.stonewu.agenteam.model.workflow.entity.WorkflowRunState.PendingCall;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.agent.FrameEventPersistence;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.execution.RunCheckpointService;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.execution.RunProgressService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 一次领取中的并行节点共用检查点和消息；各模型仍保存自己的真实框架会话。
 */
public final class WorkflowRunContext implements AutoCloseable {
    public static final String STATE_KEY = "workflow_state";
    private final RunRecord run;
    private final JobLease lease;
    private final EncryptedAgentStateStore states;
    private final FrameEventPersistence frames;
    private final RunCheckpointService checkpoints;
    private final RunProgressService progress;
    private final RunApprovalService approvals;
    private final WorkflowEventMapper events;
    private final Clock clock;
    private final Map<String, WorkflowTraversal> workflows = new LinkedHashMap<>();
    private final Map<String, WorkflowInvocationState> previous;
    private List<PendingCall> rootCalls;
    private boolean rootCompleted;
    private final Semaphore researchSlots = new Semaphore(2);
    private final Map<String, AgentState> live = new LinkedHashMap<>();
    private final Map<String, String> phases = new LinkedHashMap<>();
    private final Map<String, String> owners = new LinkedHashMap<>();
    private final List<ToolConfirmation> toolConfirmations = new ArrayList<>();
    private final List<WorkflowConfirmation> workflowConfirmations = new ArrayList<>();
    private final Map<String, Runnable> cancellations = new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public WorkflowRunContext(RunRecord run, JobLease lease, EncryptedAgentStateStore states,
                              FrameEventPersistence frames,
                              RunCheckpointService checkpoints, RunProgressService progress,
                              RunApprovalService approvals, WorkflowEventMapper events, Clock clock) {
        this.run = run;
        this.lease = lease;
        this.states = states;
        this.frames = frames;
        this.checkpoints = checkpoints;
        this.progress = progress;
        this.approvals = approvals;
        this.events = events;
        this.clock = clock;
        var saved = states.get(run.userId(), run.conversationId(), STATE_KEY, WorkflowRunState.class)
            .orElse(WorkflowRunState.empty());
        previous = saved.invocations();
        rootCalls = saved.rootCalls();
        rootCompleted = saved.rootCompleted();
    }

    public RunRecord run() {
        return run;
    }

    public JobLease lease() {
        return lease;
    }

    public EncryptedAgentStateStore states() {
        return states;
    }

    public FrameEventPersistence frames() {
        return frames;
    }

    public Semaphore researchSlots() {
        return researchSlots;
    }

    public void requireActive() {
        if (cancelled.get()) {
            throw new ExecutionStoppedException();
        }
    }

    public void cancellation(String session, Runnable action) {
        cancellations.put(session, action);
        if (cancelled.get()) {
            action.run();
        }
    }

    public void release(String session) {
        cancellations.remove(session);
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
        cancellations.values().forEach(Runnable::run);
    }

    public synchronized WorkflowInvocationState savedInvocation(String id) {
        return workflows.containsKey(id) ? workflows.get(id).snapshot() : previous.get(id);
    }

    public synchronized List<PendingCall> rootCalls() {
        return rootCalls;
    }

    public synchronized boolean rootCompleted() {
        return rootCompleted;
    }

    public synchronized void rootPaused(List<ToolUseBlock> external, List<ToolUseBlock> pending) {
        requireActive();
        finishOwner(run.conversationId());
        rootCalls = external.stream().map(
                call -> new PendingCall(call.getId(), call.getName(), JsonUtils.getJsonCodec().toJson(call.getInput())))
            .toList();
        if (!pending.isEmpty()) {
            toolConfirmations.add(new ToolConfirmation(run.conversationId(), pending));
        }
        commit(mapper -> List.of());
    }

    public synchronized void finishRoot() {
        requireActive();
        rootCompleted = true;
        rootCalls = List.of();
        finishOwner(run.conversationId());
        commit(mapper -> events.finishAgent(mapper.presentation(), run.conversationId(), "completed", clock.instant()));
    }

    public synchronized void add(Identity identity, WorkflowTraversal workflow) {
        requireActive();
        workflows.put(identity.id(), workflow);
        commit(mapper -> events.invocation(mapper.presentation(), identity, workflow.snapshot(), clock.instant()));
    }

    public synchronized void start(Identity identity, WorkflowTraversal workflow, Node node, JsonNode input) {
        requireActive();
        workflow.start(node.id(), input);
        String session = session(identity.id(), node.id());
        phases.put(session, "between_steps");
        owners.put(session, session);
        progress.boundary(lease, checkpoint(), List.of("workflow:" + identity.id() + ":" + node.id()), 0, phase());
        commit(mapper -> events.node(mapper.presentation(), identity, node, workflow.snapshot(), clock.instant()));
    }

    public synchronized void skipped(Identity identity, WorkflowTraversal workflow, List<String> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        commit(mapper -> {
            var changes = new ArrayList<ExecutionChange>();
            for (String id : nodes) {
                changes.addAll(
                    events.node(mapper.presentation(), identity, workflow.graph().nodes().get(id), workflow.snapshot(),
                        clock.instant()));
            }
            return changes;
        });
    }

    public synchronized void boundary(String owner, RuntimeContext context, List<String> tools, int modelCost,
                                      String phase) {
        requireActive();
        String session = context.getSessionId();
        var current = context.getAgentState();
        if (current == null) {
            throw new IllegalStateException("当前模型步骤缺少可保存的框架状态");
        }
        var codec = JsonUtils.getJsonCodec();
        live.put(session, codec.fromJson(codec.toJson(current), AgentState.class));
        owners.put(session, owner);
        phases.put(session, phase);
        progress.boundary(lease, checkpoint(), tools, modelCost, phase());
    }

    public synchronized void toolBoundary(String session, String toolCall, int attempt) {
        requireActive();
        owners.put(session, session);
        phases.put(session, "tools");
        progress.boundary(lease, checkpoint(), List.of(session + ":" + toolCall + ":attempt:" + attempt), 0, phase());
    }

    public synchronized void scheduleRetry(Identity identity, WorkflowTraversal workflow, Node node, String code,
                                           long at, ExecutionToolBinding binding, String call) {
        requireActive();
        workflow.scheduleRetry(node.id(), code, at);
        finishOwner(session(identity.id(), node.id()));
        var checkpoint = checkpoint();
        String phase = phase();
        frames.update(
            mapper -> events.node(mapper.presentation(), identity, node, workflow.snapshot(), clock.instant()),
            (batch, changes) -> progress.retryRead(lease, checkpoint, batch, changes, phase, binding, call));
    }

    public synchronized void startRetry(Identity identity, WorkflowTraversal workflow, Node node) {
        requireActive();
        workflow.startRetry(node.id());
        commit(mapper -> events.node(mapper.presentation(), identity, node, workflow.snapshot(), clock.instant()));
    }

    public synchronized void complete(Identity identity, WorkflowTraversal workflow, Node node, JsonNode output,
                                      String branch) {
        requireActive();
        if (!workflow.complete(node.id(), output, branch)) {
            return;
        }
        finishOwner(session(identity.id(), node.id()));
        saveNode(identity, workflow, node, "completed");
    }

    public synchronized void fail(Identity identity, WorkflowTraversal workflow, Node node, String code, String message,
                                  boolean allowContinue) {
        if (cancelled.get() || !workflow.fail(node.id(), code, message, allowContinue)) {
            return;
        }
        finishOwner(session(identity.id(), node.id()));
        saveNode(identity, workflow, node, "failed");
        if (workflow.snapshot().status().equals("failed")) {
            cancellations.values().forEach(Runnable::run);
        }
    }

    public synchronized void suspend(Identity identity, WorkflowTraversal workflow, Node node,
                                     List<ToolConfirmation> tools, WorkflowConfirmation confirmation) {
        requireActive();
        workflow.suspend(node.id());
        finishOwner(session(identity.id(), node.id()));
        toolConfirmations.addAll(tools);
        if (confirmation != null) {
            workflowConfirmations.add(confirmation);
        }
        saveNode(identity, workflow, node, "waiting_approval");
    }

    public synchronized void resume(Identity identity, WorkflowTraversal workflow, Node node) {
        requireActive();
        workflow.resume(node.id());
        String session = session(identity.id(), node.id());
        owners.put(session, session);
        phases.put(session, "between_steps");
        commit(mapper -> {
            var changes = new ArrayList<>(
                events.node(mapper.presentation(), identity, node, workflow.snapshot(), clock.instant()));
            changes.addAll(events.invocation(mapper.presentation(), identity, workflow.snapshot(), clock.instant()));
            return changes;
        });
    }

    public synchronized boolean waiting() {
        return !cancelled.get() && (!toolConfirmations.isEmpty() || !workflowConfirmations.isEmpty());
    }

    public synchronized void parkOrSave() {
        if (cancelled.get()) {
            return;
        }
        frames.close();
        if (waiting()) {
            approvals.parkAll(lease, checkpoint(), List.copyOf(toolConfirmations), List.copyOf(workflowConfirmations));
        } else {
            checkpoints.capture(run, lease, states);
        }
    }

    public synchronized void update(Function<AgentPublicEventMapper, List<ExecutionChange>> changes) {
        requireActive();
        commit(changes);
    }

    private void saveNode(Identity identity, WorkflowTraversal workflow, Node node, String outcome) {
        commit(mapper -> {
            var changes = new ArrayList<ExecutionChange>();
            changes.addAll(events.node(mapper.presentation(), identity, node, workflow.snapshot(), clock.instant()));
            changes.addAll(
                events.finishAgent(mapper.presentation(), session(identity.id(), node.id()), outcome, clock.instant()));
            changes.addAll(events.invocation(mapper.presentation(), identity, workflow.snapshot(), clock.instant()));
            return changes;
        });
    }

    private void finishOwner(String owner) {
        var sessions = owners.entrySet().stream().filter(entry -> entry.getValue().equals(owner)).map(Map.Entry::getKey)
            .toList();
        for (String session : sessions) {
            live.remove(session);
            phases.remove(session);
            owners.remove(session);
        }
    }

    private String phase() {
        return phases.containsValue("model") ? "model" : phases.containsValue("tools") ? "tools" : "between_steps";
    }

    private RunCheckpointService.Prepared checkpoint() {
        var saved = new LinkedHashMap<String, WorkflowInvocationState>(previous);
        workflows.forEach((id, value) -> saved.put(id, value.snapshot()));
        states.save(run.userId(), run.conversationId(), STATE_KEY,
            new WorkflowRunState(saved, rootCalls, rootCompleted));
        return checkpoints.prepareLiveStates(run, states, List.copyOf(live.values()));
    }

    private void commit(Function<AgentPublicEventMapper, List<ExecutionChange>> action) {
        var checkpoint = checkpoint();
        String phase = phase();
        boolean errors = workflows.values().stream().anyMatch(WorkflowTraversal::hasStepErrors);
        frames.update(action, (batch, changes) -> progress.save(lease, checkpoint, batch, changes, phase, errors));
    }

    // AgentScope 的研究任务会把会话编号用作文件名，分隔符必须能在 Windows 上使用。
    public static String session(String invocation, String node) {
        return "workflow-" + invocation + "-" + node;
    }

    @Override
    public void close() {
        frames.close();
        cancellations.values().forEach(Runnable::run);
        cancellations.clear();
    }
}
