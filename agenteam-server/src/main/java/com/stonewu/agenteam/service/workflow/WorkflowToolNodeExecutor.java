package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.agent.AgentPublicEventMapper.Scope;
import com.stonewu.agenteam.mapper.execution.RunApprovalMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.ToolConfirmation;
import com.stonewu.agenteam.model.execution.entity.ToolApprovalPolicy;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.model.workflow.entity.WorkflowNodeResult;
import com.stonewu.agenteam.service.agent.ExecutionGuardMiddleware;
import com.stonewu.agenteam.service.agent.FrameEventPersistence;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 明确配置的工具节点复用现有调用事务、参数确认、结果查询和取消实现。
 */
@Component
public class WorkflowToolNodeExecutor {
    private final ExecutionToolCatalog catalog;
    private final ToolCallTransactions transactions;
    private final ToolExecutionService tools;
    private final ToolCallMapper calls;
    private final RunApprovalMapper approvals;
    private final WorkflowValues values;
    private final Clock clock;
    private final ResourceJson json;

    public WorkflowToolNodeExecutor(ExecutionToolCatalog catalog, ToolCallTransactions transactions,
                                    ToolExecutionService tools, ToolCallMapper calls,
                                    RunApprovalMapper approvals, WorkflowValues values, Clock clock,
                                    ResourceJson json) {
        this.catalog = catalog;
        this.transactions = transactions;
        this.tools = tools;
        this.calls = calls;
        this.approvals = approvals;
        this.values = values;
        this.clock = clock;
        this.json = json;
    }

    public Mono<WorkflowNodeResult> execute(WorkflowRunContext context, Identity identity, Node node, JsonNode input,
                                            Duration timeout, int attemptNo) {
        return Mono.using(() -> prepare(context, identity, node, input, attemptNo), attempt -> Mono.fromCallable(() -> {
            if (!attempt.binding.readOnly()) {
                var approval = approvals.forTool(attempt.call);
                if (approval.isEmpty() && ToolApprovalPolicy.forRun(context.run())
                    .requiresApproval(attempt.call.operationClass())) {
                    return new WorkflowNodeResult(null, null,
                        List.of(new ToolConfirmation(attempt.session, List.of(attempt.use))), null);
                }
                if (approval.isPresent() && !approval.get().status().equals("approved")) {
                    if (!approval.get().status().equals("rejected")) {
                        throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_ALREADY_RESOLVED",
                            "当前操作确认尚不能用于执行。");
                    }
                    attempt.emit("TOOL_RESULT_END", "DENIED");
                    throw new ApiException(HttpStatus.CONFLICT, "TOOL_USER_REJECTED", "用户已拒绝本次操作，未发送请求。");
                }
            }
            attempt.emit("TOOL_RESULT_START", null);
            var output = tools.invoke(context.run(), context.lease(), attempt.binding, attempt.session, attempt.use,
                timeout, attempt.control,
                () -> context.cancelled() || attempt.cancelled.get());
            attempt.emit("TOOL_RESULT_END", output.path("isError").asBoolean(false) ? "ERROR" : "SUCCESS");
            if (output.path("isError").asBoolean(false)) {
                var call = calls.find(context.run().enterpriseId(), attempt.call.id(), false).orElseThrow();
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                    call.errorCode() == null ? "TOOL_EXECUTION_FAILED" : call.errorCode(),
                    call.errorSummary() == null ? "当前工具没有完成请求。" : call.errorSummary());
            }
            return WorkflowNodeResult.completed(output, "default");
        }), ToolAttempt::close);
    }

    private ToolAttempt prepare(WorkflowRunContext context, Identity identity, Node node, JsonNode input,
                                int attemptNo) {
        context.requireActive();
        var binding = catalog.tool(context.run(), node.config().path("pluginVersionId").asText(),
            ExecutionToolCatalog.selectionKey(node.config()));
        String session = WorkflowRunContext.session(identity.id(), node.id());
        var use = ToolUseBlock.builder().id("node-" + node.id()).name(binding.alias()).input(json.object(input))
            .content(values.write(input)).build();
        context.frames().update(mapper -> {
            String parent = mapper.presentation().id("workflow-node:" + identity.id() + ":" + node.id());
            mapper.directToolScope(new Scope(session, parent, parent, node.name()));
            mapper.platformTools(Map.of(binding.alias(), binding),
                (source, call) -> calls.framework(context.run(), source, call).orElse(null));
            return List.of();
        });
        context.toolBoundary(session, use.getId(), attemptNo);
        var stream = context.frames().stream();
        var attempt = new ToolAttempt(context, binding, session, use, stream);
        try {
            attempt.emit("TOOL_CALL_START", null);
            attempt.call = transactions.prepare(context.lease(), binding, session, null, use);
            attempt.emit("TOOL_CALL_END", null);
            context.cancellation(session, attempt::cancel);
            return attempt;
        } catch (RuntimeException failed) {
            attempt.close();
            throw failed;
        }
    }

    private final class ToolAttempt implements AutoCloseable {
        private final WorkflowRunContext context;
        private final ExecutionToolBinding binding;
        private final String session;
        private final ToolUseBlock use;
        private final FrameEventPersistence.Stream stream;
        private final AtomicReference<ToolCallControl> control = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private ToolCallRecord call;

        private ToolAttempt(WorkflowRunContext context, ExecutionToolBinding binding, String session, ToolUseBlock use,
                            FrameEventPersistence.Stream stream) {
            this.context = context;
            this.binding = binding;
            this.session = session;
            this.use = use;
            this.stream = stream;
        }

        private void emit(String type, String state) {
            stream.accept(
                new AgentStreamEvent(UUID.randomUUID().toString(), type, null, use.getId(), null, null, use.getId(),
                    binding.alias(), null,
                    state, null, clock.instant().toString(), Map.of(ExecutionGuardMiddleware.SESSION_METADATA, session,
                    ExecutionGuardMiddleware.OWNER_SESSION_METADATA, session), Map.of()));
        }

        private void cancel() {
            cancelled.set(true);
            var active = control.get();
            if (active != null) {
                active.close();
            }
        }

        @Override
        public void close() {
            try {
                stream.close();
            } finally {
                cancel();
                context.release(session);
            }
        }
    }
}
