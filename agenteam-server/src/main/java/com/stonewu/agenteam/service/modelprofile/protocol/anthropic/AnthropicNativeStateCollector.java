package com.stonewu.agenteam.service.modelprofile.protocol.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.modelprofile.entity.NativeModelState;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageMetadata;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import io.agentscope.core.model.ChatResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 仅收集官方解析未保留的签名及内容顺序，文字保存为最终消息中的位置。
 */
final class AnthropicNativeStateCollector {
    private static final int MAX_PRIVATE_CHARACTERS = 1048576;
    private final String provider;
    private final String model;
    private final ModelUsageAccumulator usage;
    private final Map<Integer, Item> items = new TreeMap<>();
    private int textLength;
    private int thinkingLength;
    private int privateCharacters;

    AnthropicNativeStateCollector(String provider, String model, ModelUsageAccumulator usage) {
        this.provider = provider;
        this.model = model;
        this.usage = usage;
    }

    void accept(RawMessageStreamEvent event) {
        if (event.isMessageStart()) {
            var message = event.asMessageStart().message();
            usage.metadata(new ModelUsageMetadata(message.id(), message.model().asString(), null, null));
            acceptUsage(JsonValue.from(message.usage()).convert(JsonNode.class));
        }
        if (event.isMessageDelta()) {
            var value = event.asMessageDelta();
            acceptUsage(JsonValue.from(value.usage()).convert(JsonNode.class));
            var delta = JsonValue.from(value.delta()).convert(JsonNode.class);
            usage.metadata(new ModelUsageMetadata(null, null, null, delta.path("stop_reason").asText(null)));
        }
        if (event.isMessageStop()) {
            usage.finish();
        }
        if (event.isContentBlockStart()) {
            var value = event.asContentBlockStart();
            JsonNode block = JsonValue.from(value.contentBlock()).convert(JsonNode.class);
            var item = new Item(block.path("type").asText(), block.path("id").asText(null));
            if (items.putIfAbsent(Math.toIntExact(value.index()), item) != null) {
                throw new IllegalStateException("模型返回了重复的内容块编号。");
            }
            if (item.kind.equals("redacted_thinking")) {
                item.attributes.put("data", privateValue(block.path("data").asText()));
            }
            if (block.hasNonNull("signature")) {
                item.signature.append(privateValue(block.path("signature").asText()));
            }
        }
        if (event.isContentBlockDelta()) {
            var value = event.asContentBlockDelta();
            var item = items.get(Math.toIntExact(value.index()));
            if (item == null) {
                throw new IllegalStateException("模型返回的内容片段没有对应的内容块。");
            }
            var delta = JsonValue.from(value.delta()).convert(JsonNode.class);
            switch (delta.path("type").asText()) {
                case "signature_delta" -> item.signature.append(privateValue(delta.path("signature").asText()));
                case "thinking_delta" -> {
                    int length = delta.path("thinking").asText().length();
                    item.append(thinkingLength, length);
                    thinkingLength = Math.addExact(thinkingLength, length);
                }
                case "text_delta" -> {
                    int length = delta.path("text").asText().length();
                    item.append(textLength, length);
                    textLength = Math.addExact(textLength, length);
                }
                default -> {
                    // 工具参数片段及其他可见内容继续由官方解析器处理。
                }
            }
        }
    }

    private void acceptUsage(JsonNode value) {
        usage.accept(value);
        usage.metadata(new ModelUsageMetadata(null, null, value.path("service_tier").asText(null), null));
    }

    ChatResponse stateResponse() {
        var state = new NativeModelState(1, "anthropic", provider, model,
            items.values().stream().map(Item::snapshot).toList());
        return ChatResponse.builder().content(List.of()).metadata(Map.of(NativeModelState.METADATA_KEY, state)).build();
    }

    private String privateValue(String value) {
        privateCharacters = Math.addExact(privateCharacters, value.length());
        if (privateCharacters > MAX_PRIVATE_CHARACTERS) {
            throw new IllegalStateException("模型返回的签名内容超过允许大小。");
        }
        return value;
    }

    private static final class Item {
        private final String kind;
        private final String toolId;
        private final List<NativeModelState.Span> spans = new ArrayList<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final StringBuilder signature = new StringBuilder();

        private Item(String kind, String toolId) {
            this.kind = kind;
            this.toolId = toolId;
        }

        private void append(int start, int length) {
            if (length == 0) {
                return;
            }
            if (!spans.isEmpty()) {
                var last = spans.getLast();
                if ((long) last.start() + last.length() == start) {
                    spans.set(spans.size() - 1, new NativeModelState.Span(last.start(), last.length() + length));
                    return;
                }
            }
            spans.add(new NativeModelState.Span(start, length));
        }

        private NativeModelState.Item snapshot() {
            if (!signature.isEmpty()) {
                attributes.put("signature", signature.toString());
            }
            return new NativeModelState.Item(kind, spans, toolId, attributes);
        }
    }
}
