package com.stonewu.agenteam.service.workflow;

import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * 只有当前仍可用的只读工具临时连接失败才再次尝试；次数和等待截止时间写入原检查点。
 */
@Component
public class WorkflowToolRetryService {
    private static final Set<String> TEMPORARY = Set.of("REMOTE_TIMEOUT", "REMOTE_CONNECTION_FAILED",
        "REMOTE_SERVER_UNAVAILABLE");
    private final ExecutionToolCatalog catalog;
    private final ToolCallMapper calls;
    private final RunLifecycleService lifecycle;
    private final Clock clock;

    public WorkflowToolRetryService(ExecutionToolCatalog catalog, ToolCallMapper calls, RunLifecycleService lifecycle,
                                    Clock clock) {
        this.catalog = catalog;
        this.calls = calls;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    public boolean plan(WorkflowRunContext context, Identity identity, WorkflowTraversal workflow, Node node,
                        ApiException error) {
        var progress = workflow.node(node.id());
        if (!node.type()
            .equals("tool") || progress.attempts() >= 3 || progress.retryAt() != null || !TEMPORARY.contains(
            error.code())) {
            return false;
        }
        var binding = catalog.tool(context.run(), node.config().path("pluginVersionId").asText(),
            ExecutionToolCatalog.selectionKey(node.config()));
        if (!binding.readOnly()) {
            return false;
        }
        var call = calls.framework(context.run(), WorkflowRunContext.session(identity.id(), node.id()),
            "node-" + node.id()).orElse(null);
        if (call == null || !call.readOnly() || !call.status().equals("failed") || !TEMPORARY.contains(
            call.errorCode())) {
            return false;
        }
        long delay = progress.attempts() == 1 ? 2000 : 10000;
        var remaining = lifecycle.remaining(context.run());
        if (progress.elapsed(clock.millis()) + delay >= node.timeoutSeconds() * 1000L
            || (!remaining.isZero() && remaining.toMillis() <= delay)) {
            return false;
        }
        context.scheduleRetry(identity, workflow, node, error.code(), clock.millis() + delay, binding, call.id());
        return true;
    }

    public Mono<Void> await(WorkflowRunContext context, Identity identity, WorkflowTraversal workflow, Node node) {
        return Mono.defer(() -> {
            context.requireActive();
            var at = workflow.node(node.id()).retryAt();
            if (at == null) {
                return Mono.empty();
            }
            return Mono.delay(Duration.ofMillis(Math.max(0, at - clock.millis())))
                .then(Mono.fromRunnable(() -> context.startRetry(identity, workflow, node)));
        });
    }
}
