package com.stonewu.agenteam.service.modelprofile.protocol.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseUsage;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageMetadata;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelRequestDiagnostics;

/**
 * 先保存原始计量，再为框架提供可读取的明细；兼容副本的默认值不进入账本。
 */
public final class OpenAIResponseUsageObserver {
    private final ModelUsageAccumulator usage;
    private final ModelRequestDiagnostics diagnostics;

    public OpenAIResponseUsageObserver(ModelUsageAccumulator usage) {
        this(usage, null);
    }

    public OpenAIResponseUsageObserver(ModelUsageAccumulator usage, ModelRequestDiagnostics diagnostics) {
        this.usage = usage;
        this.diagnostics = diagnostics;
    }

    public ResponseStreamEvent observe(ResponseStreamEvent event) {
        if (event.isError() && diagnostics != null) {
            diagnostics.captureError(JsonValue.from(event.asError()).convert(JsonNode.class));
        }
        if (event.isCompleted()) {
            var value = event.asCompleted();
            return ResponseStreamEvent.ofCompleted(value.toBuilder().response(observe(value.response(), true)).build());
        }
        if (event.isIncomplete()) {
            var value = event.asIncomplete();
            return ResponseStreamEvent.ofIncomplete(value.toBuilder().response(observe(value.response(), true)).build());
        }
        if (event.isFailed()) {
            var value = event.asFailed();
            return ResponseStreamEvent.ofFailed(value.toBuilder().response(observe(value.response(), true)).build());
        }
        if (event.isCreated()) {
            var value = event.asCreated();
            return ResponseStreamEvent.ofCreated(value.toBuilder().response(observe(value.response(), false)).build());
        }
        if (event.isInProgress()) {
            var value = event.asInProgress();
            return ResponseStreamEvent.ofInProgress(value.toBuilder().response(observe(value.response(), false)).build());
        }
        return event;
    }

    public Response observe(Response response, boolean terminal) {
        if (diagnostics != null) {
            response._error().asKnown().ifPresent(error -> diagnostics.captureError(JsonValue.from(error).convert(JsonNode.class)));
        }
        usage.metadata(new ModelUsageMetadata(text(response._id()), text(response._model()),
            text(response._serviceTier()), text(response._status())));
        var original = response._usage().asKnown();
        if (original.isPresent()) {
            usage.accept(JsonValue.from(original.get()).convert(JsonNode.class));
        }
        if (terminal) {
            usage.finish();
        }
        if (original.isEmpty()) {
            return response;
        }
        var value = original.get();
        var input = value._inputTokensDetails().asKnown()
            .orElseGet(() -> ResponseUsage.InputTokensDetails.builder().cachedTokens(0L).cacheWriteTokens(0L).build());
        var output = value._outputTokensDetails().asKnown()
            .orElseGet(() -> ResponseUsage.OutputTokensDetails.builder().reasoningTokens(0L).build());
        var compatible = value.toBuilder()
            .inputTokens(counter(value._inputTokens())).outputTokens(counter(value._outputTokens()))
            .totalTokens(counter(value._totalTokens()))
            .inputTokensDetails(input.toBuilder().cachedTokens(counter(input._cachedTokens()))
                .cacheWriteTokens(counter(input._cacheWriteTokens())).build())
            .outputTokensDetails(output.toBuilder().reasoningTokens(counter(output._reasoningTokens())).build())
            .build();
        return response.toBuilder().usage(compatible).build();
    }

    private static long counter(JsonField<Long> field) {
        // 框架只接受 int；真实 long 和异常字段已在上方原样采集，不能用此估算值计费。
        return field.asKnown().map(value -> Math.max(0L, Math.min(Integer.MAX_VALUE, value))).orElse(0L);
    }

    private static String text(JsonField<?> field) {
        return field.asKnown().map(value -> JsonValue.from(value).convert(JsonNode.class))
            .filter(JsonNode::isTextual).map(JsonNode::textValue).orElse(null);
    }
}
