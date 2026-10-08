package com.stonewu.agenteam.service.workflow;

import com.stonewu.agenteam.mapper.execution.ExecutionStepIds;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.agent.AgentInputService;
import com.stonewu.agenteam.service.execution.ExecutionTask;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

/**
 * 流程型员工在同一个后台执行中运行入口工作流，沿用提交、事件、确认与停止接口。
 */
@Component
public class WorkflowExecutionAdapter {
    private final WorkflowRunContextFactory contexts;
    private final WorkflowGraphValidator graphs;
    private final WorkflowExecutionEngine engine;
    private final WorkflowResultPublisher results;
    private final WorkflowValues values;
    private final RunLifecycleService lifecycle;
    private final AgentInputService inputs;
    private final Clock clock;

    public WorkflowExecutionAdapter(WorkflowRunContextFactory contexts, WorkflowGraphValidator graphs,
                                    WorkflowExecutionEngine engine,
                                    WorkflowResultPublisher results, WorkflowValues values,
                                    RunLifecycleService lifecycle, AgentInputService inputs, Clock clock) {
        this.contexts = contexts;
        this.graphs = graphs;
        this.engine = engine;
        this.results = results;
        this.values = values;
        this.lifecycle = lifecycle;
        this.inputs = inputs;
        this.clock = clock;
    }

    public ExecutionTask create(RunRecord run, JobLease lease) {
        var dependency = run.executionConfig().has("workflowId") ? run.executionConfig()
            : WorkflowRunDefinitions.dependency(run, "workflow",
            run.executionConfig().path("config").path("entryWorkflowVersionId").asText());
        var graph = graphs.compile(dependency.path("config"));
        String id = ExecutionStepIds.id(run, "entry-workflow");
        var identity = new Identity(id,
            dependency.has("workflowId") ? dependency.path("workflowId").asText() : dependency.path("resourceId")
                .asText(),
            dependency.path("versionId").asText(null), dependency.path("name").asText(), null, null);
        var context = contexts.open(run, lease).context();
        try {
            var initial = context.savedInvocation(id);
            if (initial == null) {
                initial = WorkflowTraversal.initial(graph, id, identity.versionId(), run.conversationId(), null,
                    values.write(inputs.workflow(run)));
            }
            return new Task(context, identity, new WorkflowTraversal(graph, initial, values, clock));
        } catch (RuntimeException failed) {
            context.close();
            throw failed;
        }
    }

    private final class Task implements ExecutionTask {
        private final WorkflowRunContext context;
        private final Identity identity;
        private final WorkflowTraversal workflow;
        private final Sinks.Empty<Void> stop = Sinks.empty();

        private Task(WorkflowRunContext context, Identity identity, WorkflowTraversal workflow) {
            this.context = context;
            this.identity = identity;
            this.workflow = workflow;
        }

        @Override
        public Mono<Void> completion() {
            var completion = engine.execute(context, identity, workflow)
                .then(Mono.<Void>fromRunnable(() -> results.publish(context, identity, workflow)))
                .takeUntilOther(stop.asMono());
            var timeout = lifecycle.remaining(context.run());
            if (!timeout.isZero()) {
                completion = completion.timeout(timeout);
            }
            return completion.subscribeOn(Schedulers.boundedElastic());
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
