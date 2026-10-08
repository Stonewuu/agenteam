package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.modelprofile.AgentModelSelectionService;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * 模型参数使用固定配置，每次实际调用重新读取启用状态和轮换后的凭据。
 */
@Component
public class AgentModelFactory {
    private final ModelProfileCatalog profiles;
    private final ModelProviderMapper providers;

    public AgentModelFactory(ModelProfileCatalog profiles, ModelProviderMapper providers) {
        this.profiles = profiles;
        this.providers = providers;
    }

    public interface ConfiguredModel extends Model {
        int maxOutputTokens();

        default boolean supportsTools() {
            return true;
        }

        default boolean supportsReasoning() {
            return false;
        }

        default boolean supportsImages() {
            return false;
        }
    }

    public ConfiguredModel create(String enterprise, String id) {
        var definition = profiles.requireAvailable(enterprise, id);
        return new ConfiguredModel() {
            @Override
            public String getModelName() {
                return definition.modelName();
            }

            @Override
            public int getContextWindowSize() {
                return definition.capabilities().maxContextTokens();
            }

            @Override
            public int maxOutputTokens() {
                return definition.capabilities().maxOutputTokens();
            }

            @Override
            public boolean supportsTools() {
                return Boolean.TRUE.equals(definition.capabilities().supportsTools());
            }

            @Override
            public boolean supportsReasoning() {
                return !definition.capabilities().reasoningEfforts().isEmpty();
            }

            @Override
            public boolean supportsImages() {
                return definition.capabilities().inputTypes().contains("image");
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    var current = profiles.requireAvailable(enterprise, id);
                    AgentModelSelectionService.validateReasoning(current.capabilities(),
                        options == null ? null : options.getReasoningEffort());
                    if (!Boolean.TRUE.equals(current.capabilities().supportsTools()) && !tools.isEmpty()) {
                        return Flux.error(new ApiException(HttpStatus.CONFLICT, "MODEL_TOOLS_UNAVAILABLE",
                            "当前模型不支持工具调用，请更换模型或调整员工配置。"));
                    }
                    String currentKey = providers.find(enterprise, current.providerId()).orElseThrow().apiKey();
                    // 模型等待由本次执行控制，避免网络读取默认五分钟提前结束长内容生成。
                    return Flux.using(() -> new OkHttpTransport(HttpTransportConfig.builder()
                        .readTimeout(Duration.ZERO).writeTimeout(Duration.ZERO).build()), transport -> {
                        var model = OpenAIChatModel.builder().modelName(current.modelName()).baseUrl(current.baseUrl())
                            .apiKey(currentKey).contextWindowSize(current.capabilities().maxContextTokens())
                            .httpTransport(transport)
                            .generateOptions(GenerateOptions.builder()
                                .executionConfig(ExecutionConfig.builder().maxAttempts(1).build()).build())
                            .stream(true).build();
                        // 先关闭本次调用的网络请求，再取消读取；不能等待暂停的读取器释放关闭锁。
                        return model.stream(messages, tools, options)
                            .doOnCancel(() -> transport.getClient().dispatcher().cancelAll());
                    }, OkHttpTransport::close);
                });
            }
        };
    }
}
