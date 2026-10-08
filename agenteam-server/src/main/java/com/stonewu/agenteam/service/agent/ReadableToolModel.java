package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.ModelImageMapper;
import com.stonewu.agenteam.mapper.agent.ModelToolNameMapper;
import com.stonewu.agenteam.service.agent.AgentModelFactory.ConfiguredModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 模型看到原工具名称，返回调用在进入执行框架前恢复为对应的内部编号。
 */
public final class ReadableToolModel implements ConfiguredModel {
    private final ConfiguredModel delegate;
    private final Supplier<Map<String, String>> names;

    public ReadableToolModel(ConfiguredModel delegate, Supplier<Map<String, String>> names) {
        this.delegate = delegate;
        this.names = names;
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }

    @Override
    public int maxOutputTokens() {
        return delegate.maxOutputTokens();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public boolean supportsReasoning() {
        return delegate.supportsReasoning();
    }

    @Override
    public boolean supportsImages() {
        return delegate.supportsImages();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            var mapper = new ModelToolNameMapper(tools, messages, names.get());
            var mapped = mapper.messages(messages);
            return delegate.stream(
                supportsImages() ? ModelImageMapper.withImageInputs(mapped) : ModelImageMapper.withoutToolImages(
                    mapped),
                mapper.tools(tools), mapper.options(options)).map(mapper::response);
        });
    }
}
