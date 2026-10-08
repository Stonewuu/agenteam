package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 框架摘要失败时保留原历史，避免将错误文字保存为对话摘要。
 */
public final class CompactionSafetyMiddleware implements MiddlewareBase {
    static final Object ATTEMPT = new Object();

    static final class Attempt {
        private final AgentState state;
        private final List<Msg> original;
        final RuntimeContext context;
        volatile Throwable failure;

        Attempt(AgentState state, RuntimeContext context) {
            this.state = state;
            this.context = context;
            original = state == null ? List.of() : new ArrayList<>(state.contextMutable());
        }

        void restore() {
            if (state != null && failure != null) {
                state.contextMutable().clear();
                state.contextMutable().addAll(original);
            }
        }
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            var attempt = new Attempt(RuntimeContext.resolveAgentState(context, agent), context);
            return next.apply(input).doOnError(ignored -> attempt.restore())
                .contextWrite(values -> values.put(ATTEMPT, attempt));
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(values -> {
            Attempt attempt = values.getOrDefault(ATTEMPT, null);
            if (attempt != null && attempt.failure != null) {
                attempt.restore();
                if (attempt.failure instanceof ApiException || attempt.failure instanceof ExecutionStoppedException) {
                    return Flux.error(attempt.failure);
                }
                return Flux.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_COMPACTION_FAILED",
                    "对话内容压缩暂未完成，已保留原始内容，请稍后重试。", attempt.failure));
            }
            // 2.0.3 的 call 异常回退会绕过自定义压缩配置并写出明文历史；此处保留安全的失败状态。
            return next.apply(input).onErrorMap(failure -> {
                String message = failure.getMessage();
                if (message != null && overflow(message)) {
                    return new ApiException(HttpStatus.BAD_GATEWAY, "EXECUTION_CONTEXT_LIMIT",
                        "本次输入仍超过模型的上下文限制，已保留对话内容，请缩小输入后重试。", failure);
                }
                return failure;
            });
        });
    }

    private boolean overflow(String message) {
        String value = message.toLowerCase(Locale.ROOT);
        return List.of("context_length_exceeded", "context length", "maximum context", "token limit", "too many tokens",
            "exceeds the model's maximum", "reduce the length").stream().anyMatch(value::contains);
    }
}
