package com.stonewu.agenteam.service.modelprofile.protocol.gemini;

import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.stonewu.agenteam.model.modelprofile.entity.NativeModelState;
import io.agentscope.core.model.ChatResponse;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 保留流式内容部分和签名的关联，工具编号缺失时使用不会碰撞的内部编号。
 */
final class GeminiNativeStateCollector {
    private final String provider;
    private final String model;
    private final List<Item> items = new ArrayList<>();
    private final Set<String> seenToolIds = new HashSet<>();
    private int textLength;
    private int thinkingLength;
    private int privateCharacters;

    GeminiNativeStateCollector(String provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    GenerateContentResponse accept(GenerateContentResponse response) {
        var candidates = response.candidates().orElse(List.of());
        if (candidates.isEmpty() || candidates.getFirst().content().isEmpty()) {
            return response;
        }
        var candidate = candidates.getFirst();
        var content = candidate.content().orElseThrow();
        var parts = content.parts().orElse(List.of());
        var normalized = new ArrayList<Part>();
        for (int index = 0; index < parts.size(); index++) {
            var part = parts.get(index);
            if (part.functionCall().isPresent()) {
                var call = part.functionCall().orElseThrow();
                boolean generated = call.id().filter(value -> !value.isBlank()).isEmpty();
                String id = generated ? UUID.randomUUID().toString() : call.id().orElseThrow();
                if (!seenToolIds.add(id)) {
                    throw new IllegalStateException("模型返回了重复的工具调用编号。");
                }
                var item = new Item("tool_use", id);
                if (generated) {
                    item.attributes.put("generated_id", true);
                }
                items.add(item);
                if (part.thoughtSignature().isPresent()) {
                    accountSignature(part.thoughtSignature().orElseThrow().length);
                }
                // 工具块自己的签名由 2.0.4 转换器保存；此处仅补完整且稳定的调用编号。
                part = part.toBuilder().functionCall(call.toBuilder().id(id).build()).build();
            } else if (part.text().isPresent()) {
                String kind = part.thought().orElse(false) ? "thinking" : "text";
                Item item;
                if (index == 0 && !items.isEmpty() && items.getLast().kind.equals(kind)
                    && !items.getLast().attributes.containsKey("signature")) {
                    item = items.getLast();
                } else {
                    item = new Item(kind, null);
                    items.add(item);
                }
                int length = part.text().orElseThrow().length();
                item.append(kind.equals("thinking") ? thinkingLength : textLength, length);
                if (kind.equals("thinking")) {
                    thinkingLength = Math.addExact(thinkingLength, length);
                } else {
                    textLength = Math.addExact(textLength, length);
                }
                if (part.thoughtSignature().isPresent()) {
                    signature(item, part.thoughtSignature().orElseThrow());
                }
            } else if (part.thoughtSignature().isPresent()) {
                if (items.isEmpty()) {
                    throw new IllegalStateException("模型返回的签名没有对应内容。");
                }
                signature(items.getLast(), part.thoughtSignature().orElseThrow());
            }
            normalized.add(part);
        }
        var updated = new ArrayList<>(candidates);
        updated.set(0, candidate.toBuilder().content(content.toBuilder().parts(normalized).build()).build());
        return response.toBuilder().candidates(updated).build();
    }

    ChatResponse stateResponse() {
        var state = new NativeModelState(1, "gemini", provider, model,
            items.stream().map(item -> new NativeModelState.Item(item.kind, item.spans, item.toolId, item.attributes)).toList());
        return ChatResponse.builder().content(List.of()).metadata(Map.of(NativeModelState.METADATA_KEY, state)).build();
    }

    private void signature(Item item, byte[] signature) {
        if (signature.length == 0) {
            return;
        }
        String encoded = Base64.getEncoder().encodeToString(signature);
        accountSignature(signature.length);
        item.attributes.put("signature", encoded);
    }

    private void accountSignature(int bytes) {
        privateCharacters = Math.addExact(privateCharacters, Math.multiplyExact((bytes + 2) / 3, 4));
        if (privateCharacters > 1048576) {
            throw new IllegalStateException("模型返回的签名内容超过允许大小。");
        }
    }

    private static final class Item {
        private final String kind;
        private final String toolId;
        private final List<NativeModelState.Span> spans = new ArrayList<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

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
    }
}
