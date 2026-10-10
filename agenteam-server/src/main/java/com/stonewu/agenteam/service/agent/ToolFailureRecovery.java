package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolFailureDetails;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import reactor.util.context.ContextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.UnaryOperator;

/** 工具错误是模型下一轮的观察结果；明确中止和无法保存执行状态的故障仍由运行层处理。 */
public final class ToolFailureRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(ToolFailureRecovery.class);
    private static final Set<String> TERMINAL_CODES = Set.of("EXECUTION_TIMEOUT", "EXECUTION_STEP_LIMIT",
        "EXECUTION_CONTEXT_LIMIT", "EXECUTION_LEASE_LOST");

    private ToolFailureRecovery() {
    }

    public static boolean terminal(Throwable error) {
        var visited = new HashSet<Throwable>();
        for (var cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ExecutionStoppedException || cause instanceof InterruptedException
                || cause instanceof CancellationException || cause instanceof DataAccessException
                || cause instanceof ApiException known && TERMINAL_CODES.contains(known.code())) {
                return true;
            }
        }
        return false;
    }

    public static ToolResultBlock result(String runId, String session, ToolUseBlock use, Exception error) {
        var privateValues = ToolFailureDetails.inputValues(use.getInput());
        var safe = ToolFailureDetails.safe(error, privateValues);
        LOG.warn("工具调用未完成，将错误交回模型继续处理，执行编号 {}，会话编号 {}，调用编号 {}", runId, session,
            use.getId(), safe);
        String code = error instanceof ApiException known ? known.code() : "TOOL_EXECUTION_FAILED";
        String message = error instanceof ApiException known ? ToolFailureDetails.clean(known.getReason(), privateValues)
            : "工具未能完成本次调用，请检查参数、缩小范围或改用其他工具继续。";
        var value = JsonNodeFactory.instance.objectNode().put("isError", true).put("code", code).put("content", message);
        return ToolResultBlock.builder().id(use.getId()).name(use.getName())
            .output(TextBlock.builder().text(value.toString()).build()).state(ToolResultState.ERROR).build();
    }

    public static void observe(Agent agent, RuntimeContext context, ContextView subscriber, ToolResultBlock result,
                               ToolUseBlock use, UnaryOperator<AgentEvent> stamp) {
        var state = RuntimeContext.resolveAgentState(context, agent);
        if (state == null) {
            throw new IllegalStateException("工具错误缺少所属智能体状态，无法继续推理");
        }
        state.contextMutable().add(ToolResultMessageBuilder.buildToolResultMsg(result, use, agent.getName()));
        String reply = UUID.randomUUID().toString();
        AgentEventEmitter.fromContext(subscriber).ifPresent(emitter -> {
            emitter.emit(stamp.apply(new ToolResultStartEvent(reply, use.getId(), use.getName())));
            emitter.emit(stamp.apply(new ToolResultTextDeltaEvent(reply, use.getId(), use.getName(),
                ((TextBlock) result.getOutput().getFirst()).getText())));
            emitter.emit(stamp.apply(new ToolResultEndEvent(reply, use.getId(), use.getName(), ToolResultState.ERROR)));
        });
    }
}
