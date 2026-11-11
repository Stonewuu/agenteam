package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.modelprofile.AgentModelSelectionService;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import com.stonewu.agenteam.service.modelprofile.ModelProtocolFactory;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型参数使用固定配置，每次实际调用重新读取启用状态和轮换后的凭据。
 */
@Component
public class AgentModelFactory {
    private final ModelProfileCatalog profiles;
    private final ModelProviderMapper providers;
    private final ModelProtocolFactory protocols;

    public AgentModelFactory(ModelProfileCatalog profiles, ModelProviderMapper providers, ModelProtocolFactory protocols) {
        this.profiles = profiles;
        this.providers = providers;
        this.protocols = protocols;
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
                return Flux.deferContextual(context -> {
                    var current = profiles.requireAvailable(enterprise, id);
                    AgentModelSelectionService.validateReasoning(current.capabilities(),
                        options == null ? null : options.getReasoningEffort());
                    if (!Boolean.TRUE.equals(current.capabilities().supportsTools()) && tools != null && !tools.isEmpty()) {
                        return Flux.error(new ApiException(HttpStatus.CONFLICT, "MODEL_TOOLS_UNAVAILABLE",
                            Messages.text("error.agent.agentModelFactory.stream.1")));
                    }
                    var provider = providers.find(enterprise, current.providerId()).filter(value -> value.enabled())
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "MODEL_UNAVAILABLE", Messages.text("error.agent.agentModelFactory.stream.2")));
                    if (!context.hasKey(ModelCallContext.class)) {
                        return Flux.error(new IllegalStateException(Messages.text("error.agent.agentModelFactory.stream.3")));
                    }
                    return protocols.stream(current, provider, context.get(ModelCallContext.class), messages,
                        tools == null ? List.of() : tools, options);
                });
            }
        };
    }
}
