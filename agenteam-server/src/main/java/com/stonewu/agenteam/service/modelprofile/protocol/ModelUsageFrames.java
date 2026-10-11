package com.stonewu.agenteam.service.modelprofile.protocol;

import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;

/**
 * 框架上下文估算使用有限整数，实际计费仍保留原生 long 与缺失状态。
 */
public final class ModelUsageFrames {
    private ModelUsageFrames() {
    }

    public static ChatResponse bounded(ChatResponse response, ModelTokenUsage actual) {
        ChatUsage original = response.getUsage();
        if (original == null) {
            return response;
        }
        var safe = ChatUsage.builder().inputTokens(counter(actual.inputTokens(), original.getInputTokens()))
            .outputTokens(counter(actual.outputTokens(), original.getOutputTokens()))
            .cachedTokens(counter(actual.cacheReadTokens(), original.getCachedTokens()))
            .cacheCreationTokens(counter(actual.cacheWriteTokens(), original.getCacheCreationTokens()))
            .reasoningTokens(counter(actual.reasoningTokens(), original.getReasoningTokens()))
            .toolUsePromptTokens(counter(actual.toolUsePromptTokens(), original.getToolUsePromptTokens()))
            .time(original.getTime()).build();
        return ChatResponse.builder().id(response.getId()).content(response.getContent()).usage(safe)
            .metadata(response.getMetadata()).finishReason(response.getFinishReason()).build();
    }

    private static int counter(Long actual, int fallback) {
        long value = actual == null ? fallback : actual;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }
}
