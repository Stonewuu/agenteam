package com.stonewu.agenteam.service.modelprofile.protocol.openai;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseStreamEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AgentScope 2.0.4 只显示思考摘要，将接口公开返回的思考正文接入同一显示入口。
 * 原始正文事件仍交给官方组装器保存续接数据，加密内容始终保持原样。
 */
final class ResponsesReasoningTextAdapter {
    private final Map<String, Source> visibleSources = new HashMap<>();

    Stream<ResponseStreamEvent> adapt(ResponseStreamEvent event) {
        if (event.isReasoningTextDelta()) {
            var delta = event.asReasoningTextDelta();
            if (!delta.delta().isEmpty() && select(delta.itemId(), Source.TEXT)) {
                var visible = ResponseReasoningSummaryTextDeltaEvent.builder().delta(delta.delta())
                    .itemId(delta.itemId()).outputIndex(delta.outputIndex()).sequenceNumber(delta.sequenceNumber())
                    .summaryIndex(delta.contentIndex()).build();
                return Stream.of(event, ResponseStreamEvent.ofReasoningSummaryTextDelta(visible));
            }
        } else if (event.isReasoningSummaryTextDelta()) {
            var delta = event.asReasoningSummaryTextDelta();
            if (!delta.delta().isEmpty() && !select(delta.itemId(), Source.SUMMARY)) {
                return Stream.empty();
            }
        }
        return Stream.of(event);
    }

    private boolean select(String itemId, Source source) {
        // 每个思考条目使用先到达的非空文本来源，避免正文与摘要被拼接显示两遍。
        return visibleSources.computeIfAbsent(itemId, ignored -> source) == source;
    }

    static Response adapt(Response response) {
        return response.toBuilder().output(response.output().stream().map(ResponsesReasoningTextAdapter::adapt).toList())
            .build();
    }

    private static ResponseOutputItem adapt(ResponseOutputItem item) {
        if (!item.isReasoning()) {
            return item;
        }
        var reasoning = item.asReasoning();
        if (reasoning.summary().stream().anyMatch(summary -> !summary.text().isEmpty())) {
            return item;
        }
        String text = reasoning.content().orElse(List.of()).stream().map(ResponseReasoningItem.Content::text)
            .collect(Collectors.joining());
        if (text.isEmpty()) {
            return item;
        }
        return ResponseOutputItem.ofReasoning(reasoning.toBuilder()
            .summary(List.of(ResponseReasoningItem.Summary.builder().text(text).build())).build());
    }

    private enum Source {
        TEXT, SUMMARY
    }
}
