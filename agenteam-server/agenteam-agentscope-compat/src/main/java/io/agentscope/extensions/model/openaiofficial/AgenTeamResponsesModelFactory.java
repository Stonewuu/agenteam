package io.agentscope.extensions.model.openaiofficial;

import com.openai.client.OpenAIClient;
import com.stonewu.agenteam.service.i18n.Messages;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;

import java.util.Objects;

/**
 * 2.0.4 尚未公开客户端注入入口；连接平台拥有的客户端，请求映射与响应组装复用官方模型。
 * 可见思考正文的兼容处理位于客户端装饰层。
 * 上游公开对应入口后可移除此目录，升级时由构造契约测试检查包内接口变化。
 */
public final class AgenTeamResponsesModelFactory {
    private AgenTeamResponsesModelFactory() {
    }

    public static OpenAIResponsesChatModel create(OpenAIClient client, GenerateOptions options,
                                                  int contextWindowSize) {
        Objects.requireNonNull(client, Messages.text("model.clientRequired"));
        Objects.requireNonNull(options, Messages.text("model.optionsRequired"));
        Objects.requireNonNull(options.getModelName(), Messages.text("model.nameRequired"));
        if (options.getApiKey() == null || options.getApiKey().isBlank()) {
            throw new IllegalArgumentException(Messages.text("model.apiKeyRequired"));
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
