package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ModelFailureMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.execution.entity.ExecutionLimits;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.model.workflow.entity.WorkflowNodeResult;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 同一张已验证的图用于正式运行和测试；最多同时运行四个已具备条件的节点。
 */
@Component
public class WorkflowExecutionEngine {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExecutionEngine.class);
    private final WorkflowControlNodes controls;
    private final WorkflowAgentNodeExecutor agents;
    private final WorkflowToolNodeExecutor tools;
    private final WorkflowApprovalNodeExecutor approvals;
    private final RunLifecycleService lifecycle;
    private final WorkflowValues values;
    private final Clock clock;
    private final ObjectMapper json;
    private final WorkflowToolRetryService retries;
    private final WorkflowResultPublisher results;

    public WorkflowExecutionEngine(WorkflowControlNodes controls, WorkflowAgentNodeExecutor agents,
                                   WorkflowToolNodeExecutor tools,
                                   WorkflowApprovalNodeExecutor approvals, RunLifecycleService lifecycle,
                                   WorkflowValues values, Clock clock, ObjectMapper json,
                                   WorkflowToolRetryService retries, WorkflowResultPublisher results) {
        this.controls = controls;
        this.agents = agents;
        this.tools = tools;
        this.approvals = approvals;
        this.lifecycle = lifecycle;
        this.values = values;
        this.clock = clock;
        this.json = json;
        this.retries = retries;
        this.results = results;
    }

    public Mono<Void> execute(WorkflowRunContext context, Identity identity, WorkflowTraversal workflow) {
        return Mono.defer(() -> {
            context.add(identity, workflow);
            return round(context, identity, workflow, true);
        });
    }

    private Mono<Void> round(WorkflowRunContext context, Identity identity, WorkflowTraversal workflow,
                             boolean restored) {
        return Mono.defer(() -> {
            context.requireActive();
            var saved = workflow.snapshot();
            if (saved.status().equals("failed")) {
                return Mono.error(new ApiException(HttpStatus.CONFLICT, saved.errorCode(), saved.errorMessage()));
            }
            if (!saved.status().equals("running")) {
                return Mono.empty();
            }
            var ready = workflow.ready();
            context.skipped(identity, workflow, ready.skipped());
            var candidates = new LinkedHashSet<>(ready.nodes());
            if (restored) {
                for (String id : workflow.graph().order()) {
                    if (Set.of("running", "waiting_approval").contains(workflow.node(id).status())) {
                        candidates.add(id);
                    }
                }
            }
            if (candidates.isEmpty()) {
                if (workflow.waiting()) {
                    return Mono.empty();
                }
                return Mono.error(new ApiException(HttpStatus.CONFLICT, "WORKFLOW_PROGRESS_INVALID",
                    "工作流没有可继续的节点，请检查连接与已保存的步骤。"));
            }
            return Flux.fromIterable(candidates)
                .flatMap(id -> node(context, identity, workflow, workflow.graph().nodes().get(id)), 4)
                .then(round(context, identity, workflow, false));
        });
    }

    private Mono<Void> node(WorkflowRunContext context, Identity identity, WorkflowTraversal workflow, Node node) {
        return Mono.defer(() -> {
            context.requireActive();
            if (workflow.node(node.id()).terminal()) {
                return Mono.empty();
            }
            if (workflow.node(node.id()).retryAt() != null) {
                return retries.await(context, identity, workflow, node).then(node(context, identity, workflow, node));
            }
            JsonNode input;
            if (workflow.node(node.id()).status().equals("pending")) {
                try {
                    input = controls.input(workflow, node);
                } catch (ApiException invalid) {
                    context.start(identity, workflow, node, json.createObjectNode());
                    return Mono.error(invalid);
                }
                context.start(identity, workflow, node, input);
            } else {
                input = values.read(workflow.node(node.id()).input());
                if (workflow.node(node.id()).status().equals("waiting_approval")) {
                    context.resume(identity, workflow, node);
                }
            }
            long remaining = node.timeoutSeconds() * 1000L - workflow.node(node.id()).elapsed(clock.millis());
            if (remaining <= 0) {
                return Mono.error(timeout());
            }
            Duration timeout = ExecutionLimits.minTimeout(Duration.ofMillis(remaining),
                lifecycle.remaining(context.run()));
            Mono<WorkflowNodeResult> result;
            if (controls.handles(node)) {
                result = Mono.fromCallable(() -> {
                    var value = controls.execute(workflow, node, input);
                    return WorkflowNodeResult.completed(value.output(), value.branch());
                });
            } else {
                result = switch (node.type()) {
                    case "agent", "skill" -> agents.execute(context, identity, node, input, timeout);
                    case "tool" ->
                        tools.execute(context, identity, node, input, timeout, workflow.node(node.id()).attempts());
                    case "approval" ->
                        Mono.fromCallable(() -> approvals.execute(context, identity, workflow, node, input));
                    default -> Mono.error(new IllegalStateException("工作流节点类型未实现"));
                };
            }
            return result.timeout(timeout).doOnNext(value -> {
                if (value.waiting()) {
                    context.suspend(identity, workflow, node, value.tools(), value.approval());
                } else {
                    if (node.type().equals("end")) {
                        results.validate(context, identity, value.output());
                    }
                    context.complete(identity, workflow, node, value.output(), value.branch());
                }
            }).then();
        }).onErrorResume(failed -> {
            Throwable cause = Exceptions.unwrap(failed);
            if (context.cancelled()) {
                return Mono.error(new ExecutionStoppedException());
            }
            if (workflow.node(node.id()).status().equals("cancelled")) {
                return Mono.empty();
            }
            LOG.error("工作流节点执行失败，执行编号 {}，工作流编号 {}，节点编号 {}", context.run().id(), identity.id(),
                node.id(), failed);
            ApiException error = cause instanceof ApiException known ? known : cause instanceof TimeoutException ? timeout()
                : ModelFailureMapper.map(cause).orElseGet(
                () -> new ApiException(HttpStatus.CONFLICT, "WORKFLOW_NODE_FAILED",
                    "当前流程节点没有完成，请查看已经保存的内容。"));
            if (retries.plan(context, identity, workflow, node, error)) {
                return retries.await(context, identity, workflow, node).then(node(context, identity, workflow, node));
            }
            context.fail(identity, workflow, node, error.code(), error.getReason(),
                cause instanceof ApiException || cause instanceof TimeoutException ? canContinue(error) : false);
            return Mono.empty();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean canContinue(ApiException error) {
        if (Set.of(401, 403, 404).contains(error.getStatusCode().value())) {
            return false;
        }
        if (error.code().startsWith("EXECUTION_") && !error.code().equals("EXECUTION_EMPTY_RESULT")) {
            return false;
        }
        return !List.of("TOOL_RESULT_UNKNOWN", "RESOURCE_DEPENDENCY_UNAVAILABLE", "QUOTA_EXCEEDED", "CONCURRENCY_LIMIT",
            "WORKFLOW_NESTING_UNSUPPORTED").contains(error.code());
    }

    private ApiException timeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "WORKFLOW_NODE_TIMEOUT",
            "当前流程节点已超过允许的执行时间。");
    }
}
