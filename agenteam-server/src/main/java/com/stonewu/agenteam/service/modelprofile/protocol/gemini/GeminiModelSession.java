package com.stonewu.agenteam.service.modelprofile.protocol.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Models;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageMetadata;
import com.stonewu.agenteam.service.modelprofile.protocol.GeminiSdkApiClient;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;

/**
 * 使用 Google 官方 Models 和 AgentScope 格式转换，补充完整计量与原生内容恢复。
 */
public final class GeminiModelSession extends ChatModelBase implements AutoCloseable {
    private final String provider;
    private final GenerateOptions defaults;
    private final ModelHttpSession transport;
    private final ModelUsageAccumulator usage;
    private final GeminiSdkApiClient client;
    private final Models models;
    private final NativeGeminiFormatter formatter;
    private final ModelProtocolOptions settings;

    public GeminiModelSession(String provider, GenerateOptions defaults, int contextWindowSize,
                              ModelHttpSession transport, ModelUsageAccumulator usage, ObjectMapper json) {
        this(provider, defaults, contextWindowSize, transport, usage, json, ModelProtocolOptions.defaults());
    }

    public GeminiModelSession(String provider, GenerateOptions defaults, int contextWindowSize,
                              ModelHttpSession transport, ModelUsageAccumulator usage, ObjectMapper json, ModelProtocolOptions settings) {
        this.provider = provider;
        this.defaults = defaults;
        this.transport = transport;
        this.usage = usage;
        this.settings = settings;
        this.formatter = new NativeGeminiFormatter(provider, defaults.getModelName());
        this.client = new GeminiSdkApiClient(defaults.getApiKey(), defaults.getBaseUrl(), transport, usage, json);
        this.models = new Models(client);
        setContextWindowSize(contextWindowSize);
    }

    @Override
    public String getModelName() {
        return defaults.getModelName();
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            var start = Instant.now();
            var collector = new GeminiNativeStateCollector(provider, getModelName());
            var config = GenerateContentConfig.builder().candidateCount(1);
            var conversation = messages;
            if (!messages.isEmpty() && messages.getFirst().getRole() == MsgRole.SYSTEM) {
                var system = formatter.format(List.of(messages.getFirst()));
                if (!system.isEmpty()) {
                    config.systemInstruction(Content.builder().parts(system.getFirst().parts().orElse(List.of())).build());
                }
                conversation = messages.subList(1, messages.size());
            }
            var formatted = formatter.format(conversation);
            formatter.applyOptions(config, options, defaults);
            var effective = GenerateOptions.mergeOptions(options, defaults);
            ModelGenerationSettings.gemini(config, effective, settings);
            if (effective.getMaxCompletionTokens() != null) {
                config.maxOutputTokens(effective.getMaxCompletionTokens());
            }
            if (tools != null && !tools.isEmpty()) {
                formatter.applyTools(config, tools);
                if (effective.getToolChoice() != null) {
                    formatter.applyToolChoice(config, effective.getToolChoice());
                }
            }
            return Flux.using(() -> models.generateContentStream(getModelName(), formatted, config.build()), stream ->
                Flux.fromIterable(stream).map(response -> {
                    observeUsage(response);
                    var normalized = collector.accept(response);
                    var parsed = formatter.parseResponse(normalized, start);
                    return ModelUsageFrames.bounded(parsed, usage.snapshot().usage());
                }), ResponseStream::close).concatWith(Mono.fromSupplier(collector::stateResponse));
        }).subscribeOn(Schedulers.boundedElastic()).doOnCancel(transport::cancel);
    }

    private void observeUsage(GenerateContentResponse response) {
        String finish = response.candidates().orElse(List.of()).stream().findFirst()
            .flatMap(value -> value.finishReason().map(Object::toString)).orElse(null);
        usage.metadata(new ModelUsageMetadata(response.responseId().orElse(null), response.modelVersion().orElse(null), null, finish));
        if (finish != null) {
            usage.finish();
        }
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
