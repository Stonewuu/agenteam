package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentStreamEventMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.execution.ExecutionPresentationFactory;
import com.stonewu.agenteam.service.execution.ExecutionTask;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.execution.RunCheckpointService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.collaboration.CollaborationRunAccessService;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.Exceptions;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 继续使用既有 AgentScope 事件映射和片段合并，事件先保存再由独立连接读取。
 */
@Component
public class AgentExecutionAdapter {
    private final AgentExecutionFactory factory;
    private final AgentStreamEventMapper events;
    private final ExecutionMessageMapper messages;
    private final ExecutionPresentationFactory presentations;
    private final RunCheckpointService checkpoints;
    private final AgentInputService inputs;
    private final RunApprovalService approvals;
    private final CollaborationRunAccessService collaboration;

    public AgentExecutionAdapter(AgentExecutionFactory factory, AgentStreamEventMapper events,
                                 ExecutionMessageMapper messages,
                                 ExecutionPresentationFactory presentations, RunCheckpointService checkpoints,
                                 AgentInputService inputs, RunApprovalService approvals, CollaborationRunAccessService collaboration) {
        this.factory = factory;
        this.events = events;
        this.messages = messages;
        this.presentations = presentations;
        this.checkpoints = checkpoints;
        this.inputs = inputs;
        this.approvals = approvals;
        this.collaboration = collaboration;
    }

    public Task create(RunRecord run, JobLease lease) {
        var execution = factory.create(run, lease);
        FrameEventPersistence persistence = null;
        try {
            persistence = presentations.open(run, lease);
            persistence.update(mapper -> {
                mapper.platformTools(execution.tools().bindings(), execution.tools()::find);
                return List.of();
            });
            execution.guard().beforeStep(persistence::flush);
            var initial = inputs.message(run);
            return new Task(execution, persistence,
                approvals.input(run, execution.states(), initial, execution.restored()), run, lease, true);
        } catch (RuntimeException error) {
            if (persistence != null) {
                persistence.close();
            }
            execution.close();
            throw error;
        }
    }

    public Task createScoped(RunRecord run, JobLease lease, AgentExecutionFactory.Execution execution,
                             FrameEventPersistence persistence, List<Msg> input) {
        persistence.update(mapper -> {
            mapper.platformTools(execution.tools().bindings(), execution.tools()::find);
            return List.of();
        });
        execution.guard().beforeStep(persistence::flush);
        return new Task(execution, persistence, input, run, lease, false);
    }

    public final class Task implements ExecutionTask {
        private final AgentExecutionFactory.Execution execution;
        private final FrameEventPersistence persistence;
        private final FrameEventPersistence.Stream stream;
        private final AgentRun<AgentEvent> invocation;
        private final RunRecord run;
        private final JobLease lease;
        private final boolean ownsPersistence;
        private final Sinks.Empty<Void> stop = Sinks.empty();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile List<ToolUseBlock> pending = List.of();
        private volatile List<ToolUseBlock> external = List.of();
        private volatile Msg result;
        private volatile boolean collaborationFinished;

        private Task(AgentExecutionFactory.Execution execution, FrameEventPersistence persistence, List<Msg> input,
                     RunRecord run, JobLease lease, boolean ownsPersistence) {
            this.execution = execution;
            this.persistence = persistence;
            this.invocation = execution.agent().prepareRun(List.copyOf(input), execution.context());
            this.run = run;
            this.stream = persistence.stream();
            this.lease = lease;
            this.ownsPersistence = ownsPersistence;
        }

        public Mono<Void> completion() {
            var completion = invocation.stream().doOnNext(event -> {
                    if (event instanceof RequireUserConfirmEvent confirmation) {
                        if (event.getSource() != null) {
                            throw new IllegalStateException("子任务不能申请外部写入确认");
                        }
                        pending = confirmation.getToolCalls();
                    }
                    if (event instanceof RequireExternalExecutionEvent waiting) {
                        if (event.getSource() != null) {
                            throw new IllegalStateException("子任务不能启动工作流");
                        }
                        external = waiting.getToolCalls();
                    }
                    if (event instanceof AgentResultEvent completed && event.getSource() == null) {
                        result = completed.getResult();
                    }
                }).filter(
                    event -> !(event instanceof RequireUserConfirmEvent) && !(event instanceof RequireExternalExecutionEvent)
                        && !(event instanceof ExternalExecutionResultEvent)).map(events::map).doOnNext(stream::accept)
                .takeUntilOther(stop.asMono()).takeUntilOther(persistence.failure().asMono()).then()
                // 新版用取消异常通知结束，仅把平台主动取消的本次调用转为正常停止。
                .onErrorComplete(this::normalEnd)
                .doOnSuccess(ignored -> {
                    stream.finish(cancelled.get() ? "cancelled" : "completed");
                    persistence.flush();
                    execution.tools().requireSuccessful();
                    if (ownsPersistence && !cancelled.get() && !waiting() && !collaborationFinished && messages.find(run.enterpriseId(),
                        run.conversationId(), run.outputMessageId()).orElseThrow().content().isBlank()) {
                        throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_EMPTY_RESULT",
                            "员工没有返回可显示的回复，请稍后重试。");
                    }
                    completed.set(true);
                });
            if (!execution.timeout().isZero()) {
                completion = completion.timeout(execution.timeout());
            }
            return completion.subscribeOn(Schedulers.boundedElastic());
        }

        private boolean normalEnd(Throwable failure) {
            var cause = Exceptions.unwrap(failure);
            if (cancelled.get() && cause instanceof CancellationException) {
                return true;
            }
            if (cause instanceof ApiException api && api.code().equals("COLLABORATION_WORK_FINISHED")
                && collaboration.hasCommittedRoundEnd(run)) {
                collaborationFinished = true;
                return true;
            }
            return false;
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                execution.guard().cancel();
                invocation.cancel();
                stop.tryEmitEmpty();
            }
        }

        public boolean cancelled() {
            return cancelled.get();
        }

        public boolean waiting() {
            return !cancelled.get() && (!pending.isEmpty() || !external.isEmpty());
        }

        public List<ToolUseBlock> pendingApprovals() {
            return pending;
        }

        public List<ToolUseBlock> externalCalls() {
            return external;
        }

        public Msg result() {
            return result;
        }

        public void saveCheckpoint() {
            if (!ownsPersistence) {
                throw new IllegalStateException("流程节点的检查点必须随整个执行一起保存");
            }
            close();
            if (cancelled.get()) {
                return;
            }
            if (!external.isEmpty()) {
                throw new IllegalStateException("外部工作流必须由工作流执行器保存和继续");
            }
            if (waiting()) {
                approvals.park(lease, checkpoints.prepare(run, execution.states(), null), pending);
            } else {
                checkpoints.capture(run, lease, execution.states());
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (!completed.get()) {
                    execution.guard().cancel();
                    invocation.cancel();
                }
                try {
                    stream.close();
                } finally {
                    if (ownsPersistence) {
                        persistence.close();
                    }
                    execution.close();
                }
            }
        }
    }
}
