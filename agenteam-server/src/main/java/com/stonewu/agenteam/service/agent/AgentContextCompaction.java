package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.agent.AgentModelFactory.ConfiguredModel;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按模型可用输入空间压缩历史，摘要继续使用当前模型的输出限制。
 */
public final class AgentContextCompaction {
    private static final Logger LOG = LoggerFactory.getLogger(AgentContextCompaction.class);

    private AgentContextCompaction() {
    }

    public static int threshold(ConfiguredModel model) {
        long available = (long) model.getContextWindowSize() - model.maxOutputTokens();
        if (model.maxOutputTokens() < 1 || available < 2) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_CONTEXT_INVALID",
                "模型的最大上下文必须大于最大输出，请检查模型配置。");
        }
        return (int) (available * 95 / 100);
    }

    public static CompactionConfig create(ConfiguredModel model, GenerateOptions options, String runId) {
        return create(model, options, runId, null);
    }

    public static CompactionConfig create(ConfiguredModel model, GenerateOptions options, String runId,
                                          ExecutionGuardMiddleware guard) {
        int trigger = threshold(model);
        return CompactionConfig.builder().triggerMessages(0).triggerTokens(trigger)
            .keepTokens(Math.max(1, Math.min(8000, trigger / 4)))
            // 完整历史由平台保存，摘要进入已有加密状态，不额外写明文历史或长期记忆。
            .flushBeforeCompact(false).offloadBeforeCompact(false).prune(null)
            .model(new Model() {
                @Override
                public String getModelName() {
                    return model.getModelName();
                }

                @Override
                public int getContextWindowSize() {
                    return model.getContextWindowSize();
                }

                @Override
                public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions ignored) {
                    // 框架摘要调用传入空的工具参数；平台模型适配器需要明确的空集合。
                    return Flux.deferContextual(values -> {
                        var hasText = new AtomicBoolean();
                        CompactionSafetyMiddleware.Attempt attempt = values.getOrDefault(
                            CompactionSafetyMiddleware.ATTEMPT, null);
                        Mono<Void> permission = guard == null ? Mono.empty() : attempt == null
                            ? Mono.error(new IllegalStateException("上下文压缩缺少执行归属")) : guard.beforeCompaction(
                            attempt.context);
                        return permission.thenMany(Flux.defer(() -> model.stream(messages, List.of(), options)))
                            .doOnNext(response -> {
                                if (response.getContent().stream().anyMatch(block -> block instanceof TextBlock text
                                    && text.getText() != null && !text.getText().isBlank())) {
                                    hasText.set(true);
                                }
                            }).concatWith(Flux.defer(() -> hasText.get() ? Flux.empty()
                                : Flux.error(new IllegalStateException("模型未返回可用的上下文摘要"))))
                            .doOnError(failure -> {
                                if (attempt != null) {
                                    attempt.failure = failure;
                                }
                                LOG.warn("上下文压缩失败，执行编号 {}", runId, failure);
                            });
                    });
                }
            }).build();
    }
}
