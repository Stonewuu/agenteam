package com.stonewu.agenteam.service.modelprofile.protocol.anthropic;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.stonewu.agenteam.service.modelprofile.protocol.AnthropicSdkHttpClient;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelHttpSession;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelUsageFrames;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelGenerationSettings;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProtocolOptions;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicResponseParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;

/**
 * 官方开发库与格式转换之间的薄连接，平台拥有本次请求及原生状态采集。
 */
public final class AnthropicModelSession extends ChatModelBase implements AutoCloseable {
    private final String provider;
    private final GenerateOptions defaults;
    private final ModelHttpSession transport;
    private final ModelUsageAccumulator usage;
    private final AnthropicClient client;
    private final NativeAnthropicFormatter formatter;
    private final ModelProtocolOptions settings;

    public AnthropicModelSession(String provider, GenerateOptions options, int contextWindowSize, String cacheTtl,
                                 ModelHttpSession transport, ModelUsageAccumulator usage) {
        this(provider, options, contextWindowSize, cacheTtl, transport, usage, ModelProtocolOptions.defaults());
    }

    public AnthropicModelSession(String provider, GenerateOptions options, int contextWindowSize, String cacheTtl,
                                 ModelHttpSession transport, ModelUsageAccumulator usage, ModelProtocolOptions settings) {
        this.provider = provider;
        this.defaults = options;
        this.transport = transport;
        this.usage = usage;
        this.settings = settings;
        this.formatter = new NativeAnthropicFormatter(provider, options.getModelName());
        formatter.cacheTtl(cacheTtl);
        setContextWindowSize(contextWindowSize);
        var backend = AnthropicBackend.builder().apiKey(options.getApiKey()).baseUrl(options.getBaseUrl()).build();
        var http = new AnthropicSdkHttpClient(transport, backend);
        try {
            this.client = new AnthropicClientImpl(ClientOptions.builder().httpClient(http)
                .baseUrl(options.getBaseUrl()).maxRetries(0).build());
        } catch (RuntimeException | Error failure) {
            http.close();
            throw failure;
        }
    }

    @Override
    public String getModelName() {
        return defaults.getModelName();
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            var start = Instant.now();
            var collector = new AnthropicNativeStateCollector(provider, getModelName(), usage);
            var params = MessageCreateParams.builder().model(getModelName()).maxTokens(4096);
            var effective = GenerateOptions.mergeOptions(options, defaults);
            boolean cache = Boolean.TRUE.equals(effective.getCacheControl());
            formatter.applySystemMessage(params, messages, cache);
            var conversation = !messages.isEmpty() && messages.getFirst().getRole() == MsgRole.SYSTEM
                ? messages.subList(1, messages.size()) : messages;
            var formatted = formatter.format(conversation);
            if (cache) {
                formatted = formatter.applyCacheControl(formatted);
            }
            formatted.forEach(params::addMessage);
            formatter.applyOptions(params, options, defaults);
            // 即使没有工具也调用公开入口，使格式转换器清理本次暂存选项。
            formatter.applyTools(params, tools == null ? List.of() : tools);
            ModelGenerationSettings.anthropic(params, effective, settings);
            return Flux.using(() -> client.messages().createStreaming(params.build()), stream ->
                    AnthropicResponseParser.parseStreamEvents(Flux.fromStream(stream.stream()).doOnNext(collector::accept), start),
                    StreamResponse::close)
                .map(response -> ModelUsageFrames.bounded(response, usage.snapshot().usage()))
                .concatWith(Mono.fromSupplier(collector::stateResponse));
        }).subscribeOn(Schedulers.boundedElastic()).doOnCancel(transport::cancel);
    }

    public void cancel() {
        transport.cancel();
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            transport.close();
        }
    }
}
