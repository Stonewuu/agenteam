package com.stonewu.agenteam.service.modelprofile.protocol.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageMetadata;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelRequestDiagnostics;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 在官方 Chat 响应转换前采集用量，流和消息协议仍由原有传输与模型处理。
 */
public final class ObservedChatTransport implements HttpTransport, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ObservedChatTransport.class);
    private final HttpTransport delegate;
    private final ModelUsageAccumulator usage;
    private final ObjectMapper json;
    private final String callId;
    private final ModelRequestDiagnostics diagnostics;

    public ObservedChatTransport(HttpTransport delegate, ModelUsageAccumulator usage, ObjectMapper json, String callId) {
        this(delegate, usage, json, callId, null);
    }

    public ObservedChatTransport(HttpTransport delegate, ModelUsageAccumulator usage, ObjectMapper json, String callId,
                                  ModelRequestDiagnostics diagnostics) {
        this.delegate = delegate;
        this.usage = usage;
        this.json = json;
        this.callId = callId;
        this.diagnostics = diagnostics;
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        var response = delegate.execute(request);
        if (response.isSuccessful()) {
            observe(response.getBody());
        }
        return response;
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(request).doOnNext(this::observe);
    }

    private void observe(String data) {
        if (data == null || data.isBlank()) {
            return;
        }
        try {
            var response = json.readTree(data);
            usage.accept(response.get("usage"));
            if (response.hasNonNull("error") && diagnostics != null) {
                diagnostics.captureError(response.get("error"));
                throw new IllegalStateException("模型在响应流中返回错误。");
            }
            String reason = null;
            for (JsonNode choice : response.path("choices")) {
                if (choice.hasNonNull("finish_reason")) {
                    reason = choice.path("finish_reason").asText();
                }
            }
            usage.metadata(new ModelUsageMetadata(response.path("id").asText(null), response.path("model").asText(null),
                response.path("service_tier").asText(null), reason));
            if (reason != null) {
                usage.finish();
            }
        } catch (JsonProcessingException failure) {
            // 原始解析异常可能包含模型文本，只记录位置堆栈，协议错误继续由官方模型处理。
            var safe = new IllegalStateException("模型用量事件不是有效的 JSON。");
            safe.setStackTrace(failure.getStackTrace());
            LOG.warn("模型用量事件无法解析，调用编号={}", callId, safe);
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
