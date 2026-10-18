package com.stonewu.agenteam.service.modelprofile.protocol.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelRequestDiagnostics;

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 装饰公开开发库接口，仍使用官方请求、事件解码和工具组装。
 */
public final class ObservedOpenAIClient extends OpenAIClientDelegate {
    private final ModelUsageAccumulator usage;
    private final ModelRequestDiagnostics diagnostics;
    private final ResponseService responses;

    public ObservedOpenAIClient(OpenAIClient delegate, ModelUsageAccumulator usage) {
        this(delegate, usage, null);
    }

    public ObservedOpenAIClient(OpenAIClient delegate, ModelUsageAccumulator usage, ModelRequestDiagnostics diagnostics) {
        super(delegate);
        this.usage = usage;
        this.diagnostics = diagnostics;
        this.responses = new ObservedResponses(delegate.responses(), new OpenAIResponseUsageObserver(usage, diagnostics));
    }

    @Override
    public ResponseService responses() {
        return responses;
    }

    @Override
    public OpenAIClient withOptions(Consumer<ClientOptions.Builder> options) {
        return new ObservedOpenAIClient(delegate.withOptions(options), usage, diagnostics);
    }

    private static final class ObservedResponses extends OpenAIResponseServiceDelegate {
        private final OpenAIResponseUsageObserver observer;

        private ObservedResponses(ResponseService delegate, OpenAIResponseUsageObserver observer) {
            super(delegate);
            this.observer = observer;
        }

        @Override
        public ResponseService withOptions(Consumer<ClientOptions.Builder> options) {
            return new ObservedResponses(delegate.withOptions(options), observer);
        }

        @Override
        public Response create(ResponseCreateParams params, RequestOptions options) {
            return ResponsesReasoningTextAdapter.adapt(observer.observe(delegate.create(params, options), true));
        }

        @Override
        public StreamResponse<ResponseStreamEvent> createStreaming(ResponseCreateParams params, RequestOptions options) {
            var original = delegate.createStreaming(params, options);
            var reasoning = new ResponsesReasoningTextAdapter();
            return new StreamResponse<>() {
                @Override
                public Stream<ResponseStreamEvent> stream() {
                    return original.stream().map(observer::observe).flatMap(reasoning::adapt);
                }

                @Override
                public void close() {
                    original.close();
                }
            };
        }
    }
}
