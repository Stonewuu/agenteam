package com.stonewu.agenteam.service.modelprofile.protocol;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingConfig;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProtocolOptions;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;

import java.util.Locale;

/**
 * 共享选择项转换成管理员配置的原生参数，不猜测型号或思考预算。
 */
public final class ModelGenerationSettings {
    private ModelGenerationSettings() {
    }

    public static GenerateOptions options(ModelProfileRecord model, ModelProviderRecord provider, GenerateOptions input) {
        var requested = input == null ? GenerateOptions.builder().build() : input;
        var capability = model.capabilities();
        var nativeOptions = capability.protocolOptions();
        Integer requestedMax = requested.getMaxCompletionTokens() == null ? requested.getMaxTokens() : requested.getMaxCompletionTokens();
        int maximum = requestedMax == null ? capability.maxOutputTokens() : Math.min(requestedMax, capability.maxOutputTokens());
        boolean completionLimit = provider.protocol().equals("openai_responses")
            || (provider.protocol().equals("openai") && requested.getMaxCompletionTokens() != null);
        var builder = GenerateOptions.builder().apiKey(provider.apiKey()).baseUrl(provider.baseUrl()).modelName(model.modelName())
            .maxTokens(completionLimit ? null : maximum).maxCompletionTokens(completionLimit ? maximum : null).stream(true)
            .temperature(Boolean.TRUE.equals(capability.supportsTemperature()) ? requested.getTemperature() : null)
            .topP(requested.getTopP()).topK(requested.getTopK()).seed(requested.getSeed())
            .presencePenalty(requested.getPresencePenalty()).frequencyPenalty(requested.getFrequencyPenalty())
            .reasoningEffort(requested.getReasoningEffort()).toolChoice(requested.getToolChoice())
            .parallelToolCalls(requested.getParallelToolCalls()).responseFormat(requested.getResponseFormat())
            .cacheControl(provider.protocol().equals("anthropic") ? nativeOptions.cacheTtlSeconds() > 0 : null)
            .executionConfig(ExecutionConfig.builder().maxAttempts(1)
                .timeout(requested.getExecutionConfig() == null ? null : requested.getExecutionConfig().getTimeout()).build());
        if (nativeOptions.serviceTier() != null) {
            builder.additionalBodyParam("service_tier", nativeOptions.serviceTier());
        }
        if (nativeOptions.reasoningMode().equals("budget") && requested.getReasoningEffort() != null) {
            builder.thinkingBudget(nativeOptions.thinkingBudgets().get(requested.getReasoningEffort()));
        }
        return builder.build();
    }

    public static void anthropic(MessageCreateParams.Builder params, GenerateOptions options, ModelProtocolOptions settings) {
        if (options.getMaxCompletionTokens() != null) {
            params.maxTokens(options.getMaxCompletionTokens());
        }
        String effort = options.getReasoningEffort();
        if (effort == null) {
            return;
        }
        if (effort.equals("none")) {
            params.thinking(ThinkingConfigDisabled.builder().build());
        } else if (settings.reasoningMode().equals("adaptive")) {
            params.thinking(ThinkingConfigAdaptive.builder().build());
            params.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(settings.thinkingLevels().get(effort))).build());
        } else {
            params.thinking(ThinkingConfigEnabled.builder().budgetTokens(settings.thinkingBudgets().get(effort)).build());
        }
    }

    public static void gemini(GenerateContentConfig.Builder params, GenerateOptions options, ModelProtocolOptions settings) {
        if (options.getReasoningEffort() != null && settings.reasoningMode().equals("level")) {
            params.thinkingConfig(ThinkingConfig.builder().includeThoughts(true)
                .thinkingLevel(settings.thinkingLevels().get(options.getReasoningEffort()).toUpperCase(Locale.ROOT)).build());
        }
    }
}
