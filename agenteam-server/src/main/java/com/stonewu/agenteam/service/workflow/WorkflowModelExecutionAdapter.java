package com.stonewu.agenteam.service.workflow;

import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionStepIds;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.workflow.entity.WorkflowRunState.PendingCall;
import com.stonewu.agenteam.service.agent.AgentExecutionAdapter;
import com.stonewu.agenteam.service.agent.AgentExecutionFactory;
import com.stonewu.agenteam.service.agent.AgentInputService;
import com.stonewu.agenteam.service.execution.ExecutionTask;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workflow.WorkflowToolCatalog.Binding;
import io.agentscope.core.message.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话模型等待真实工作流结果后继续；暂停期间仍保留原对话、工具编号和同一顶层执行。
 */
@Component
public class WorkflowModelExecutionAdapter {
    private record ModelPause(List<ToolUseBlock> external, List<ToolUseBlock> confirmations) {
    }

    private final WorkflowRunContextFactory contexts;
    private final WorkflowToolCatalog catalog;
    private final WorkflowExecutionEngine engine;
    private final WorkflowValues values;
    private final AgentExecutionFactory agents;
    private final AgentExecutionAdapter adapter;
    private final AgentInputService inputs;
    private final RunApprovalService approvals;
    private final RunLifecycleService lifecycle;
    private final ExecutionMessageMapper messages;
    private final Clock clock;

    public WorkflowModelExecutionAdapter(WorkflowRunContextFactory contexts, WorkflowToolCatalog catalog,
                                         WorkflowExecutionEngine engine,
                                         WorkflowValues values, AgentExecutionFactory agents,
                                         AgentExecutionAdapter adapter, AgentInputService inputs,
                                         RunApprovalService approvals, RunLifecycleService lifecycle,
                                         ExecutionMessageMapper messages, Clock clock) {
        this.contexts = contexts;
        this.catalog = catalog;
        this.engine = engine;
        this.values = values;
        this.agents = agents;
        this.adapter = adapter;
        this.inputs = inputs;
        this.approvals = approvals;
        this.lifecycle = lifecycle;
        this.messages = messages;
        this.clock = clock;
    }

    public ExecutionTask create(RunRecord run, JobLease lease) {
        var bindings = catalog.list(run);
        var opened = contexts.open(run, lease);
        var context = opened.context();
        try {
            if (!opened.restored()) {
                agents.initializeHistory(run, context.states());
            }
            context.frames().update(mapper -> {
                mapper.workflowTools(bindings.keySet());
                return List.of();
            });
            return new Task(context, bindings, opened.restored());
        } catch (RuntimeException failed) {
            context.close();
            throw failed;
        }
    }

    private final class Task implements ExecutionTask {
        private final WorkflowRunContext context;
        private final Map<String, Binding> bindings;
        private final Sinks.Empty<Void> stop = Sinks.empty();
        private boolean restored;

        private Task(WorkflowRunContext context, Map<String, Binding> bindings, boolean restored) {
            this.context = context;
            this.bindings = bindings;
            this.restored = restored;
        }

        @Override
        public Mono<Void> completion() {
            var completion = cycle().takeUntilOther(stop.asMono());
            var timeout = lifecycle.remaining(context.run());
            if (!timeout.isZero()) {
                completion = completion.timeout(timeout);
            }
            return completion.subscribeOn(Schedulers.boundedElastic());
        }

        private Mono<Void> cycle() {
            return pendingFlows().then(Mono.defer(() -> {
                context.requireActive();
                if (context.waiting() || context.rootCompleted()) {
                    return Mono.empty();
                }
                return invokeModel().flatMap(pause -> {
                    if (pause.external().isEmpty() && pause.confirmations().isEmpty()) {
                        var run = context.run();
                        if (messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow()
                            .content().isBlank()) {
                            return Mono.error(new ApiException(HttpStatus.CONFLICT, "EXECUTION_EMPTY_RESULT",
                                "员工没有返回可显示的回复，请稍后重试。"));
                        }
                        context.finishRoot();
                        return Mono.empty();
                    }
                    context.rootPaused(pause.external(), pause.confirmations());
                    return cycle();
                });
            }));
        }

        private Mono<Void> pendingFlows() {
            return Flux.defer(() -> Flux.fromIterable(context.rootCalls())).concatMap(call -> {
                var binding = binding(call);
                String id = invocationId(call);
                var saved = context.savedInvocation(id);
                if (saved != null && (!saved.versionId().equals(binding.versionId()) || !values.read(saved.input())
                    .equals(values.read(call.input())))) {
                    return Mono.error(new ApiException(HttpStatus.CONFLICT, "WORKFLOW_CALL_CHANGED",
                        "同一工作流调用编号不能用于不同版本或参数。"));
                }
                if (saved == null) {
                    saved = WorkflowTraversal.initial(binding.graph(), id, binding.versionId(),
                        context.run().conversationId(), call.id(), call.input());
                }
                String parent = ExecutionStepIds.id(context.run(), "agent:" + context.run().conversationId());
                var identity = new Identity(id, binding.resourceId(), binding.versionId(), binding.name(), parent,
                    null);
                return engine.execute(context, identity, new WorkflowTraversal(binding.graph(), saved, values, clock));
            }).then();
        }

        private Mono<ModelPause> invokeModel() {
            return Mono.using(this::modelTask, task -> task.completion()
                    .then(Mono.fromCallable(() -> new ModelPause(task.externalCalls(), task.pendingApprovals()))),
                task -> {
                    try {
                        task.close();
                    } finally {
                        context.release(context.run().conversationId());
                    }
                });
        }

        private AgentExecutionAdapter.Task modelTask() {
            context.requireActive();
            var run = context.run();
            var session = run.conversationId();
            List<Msg> messages = new ArrayList<>(approvals.input(run, context.states(), inputs.message(run), restored));
            for (var call : context.rootCalls()) {
                var binding = binding(call);
                var saved = context.savedInvocation(invocationId(call));
                if (saved == null || !saved.status().equals("completed")) {
                    throw new IllegalStateException("工作流尚未完成，不能向模型提交成功结果");
                }
                String output = saved.nodes().get(binding.graph().endId()).output();
                // 2.0.3 的 OpenAI 转换器每条工具消息只读取第一项，必须按调用分别提交消息。
                var result = ToolResultBlock.builder().id(call.id()).name(call.name()).state(ToolResultState.SUCCESS)
                    .output(TextBlock.builder().text(output).build()).build();
                messages.add(new ToolResultMessage(result));
            }
            var execution = agents.createScoped(run, context.lease(), run.executionConfig().path("config"),
                run.executionConfig().path("agentId").asText(null), run.executionConfig().path("name").asText(),
                session, context.states(), restored, lifecycle.remaining(run), ignored -> {
                });
            try {
                execution.guard().researchSlots(context.researchSlots());
                execution.guard()
                    .boundary((runtime, tools, cost, phase) -> context.boundary(session, runtime, tools, cost, phase));
                execution.tools().externalTools(execution.agent(), catalog.tools(bindings));
                var task = adapter.createScoped(run, context.lease(), execution, context.frames(), messages);
                restored = true;
                context.cancellation(session, task::cancel);
                return task;
            } catch (RuntimeException failed) {
                execution.close();
                throw failed;
            }
        }

        private Binding binding(PendingCall call) {
            var binding = bindings.get(call.name());
            if (binding == null) {
                throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
                    "模型请求的工作流不属于本次固定能力。");
            }
            return binding;
        }

        private String invocationId(PendingCall call) {
            return ExecutionStepIds.id(context.run(),
                "workflow-call:" + context.run().conversationId() + ":" + call.id());
        }

        @Override
        public void cancel() {
            context.cancel();
            stop.tryEmitEmpty();
        }

        @Override
        public boolean cancelled() {
            return context.cancelled();
        }

        @Override
        public boolean waiting() {
            return context.waiting();
        }

        @Override
        public void saveCheckpoint() {
            context.parkOrSave();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
