package io.agentscope.extensions.model.openaiofficial;

import com.openai.client.OpenAIClient;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;

import java.util.Objects;

/**
 * 2.0.4 尚未公开客户端注入入口；仅连接平台拥有的客户端，协议转换全部使用官方模型。
 * 上游公开对应入口后可移除此目录，升级时由构造契约测试检查包内接口变化。
 */
public final class AgenTeamResponsesModelFactory {
    private AgenTeamResponsesModelFactory() {
    }

    public static OpenAIResponsesChatModel create(OpenAIClient client, GenerateOptions options,
                                                  int contextWindowSize) {
        Objects.requireNonNull(client, "模型客户端不能为空");
        Objects.requireNonNull(options, "模型配置不能为空");
        Objects.requireNonNull(options.getModelName(), "模型名称不能为空");
        if (options.getApiKey() == null || options.getApiKey().isBlank()) {
            throw new IllegalArgumentException("模型密钥不能为空");
        }
        var controlled = GenerateOptions.mergeOptions(GenerateOptions.builder()
            .executionConfig(ExecutionConfig.builder().maxAttempts(1).build()).build(), options);
        return new ConfiguredResponsesModel(client, controlled, contextWindowSize);
    }

    private static final class ConfiguredResponsesModel extends OpenAIResponsesChatModel {
        private ConfiguredResponsesModel(OpenAIClient client, GenerateOptions options, int contextWindowSize) {
            super(client, options, options.getApiKey(), options.getBaseUrl(), null, null, null);
            setContextWindowSize(contextWindowSize);
            applyNativeStructuredOutputDefaults();
        }
    }
}
