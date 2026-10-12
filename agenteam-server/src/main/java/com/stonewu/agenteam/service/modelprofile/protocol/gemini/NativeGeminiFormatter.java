package com.stonewu.agenteam.service.modelprofile.protocol.gemini;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.stonewu.agenteam.model.modelprofile.entity.NativeModelState;
import com.stonewu.agenteam.service.modelprofile.protocol.NativeModelHistory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.model.gemini.formatter.GeminiChatFormatter;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 复用工具和媒体转换，补回普通文本及思考内容部分的签名，保持原始部分顺序。
 */
final class NativeGeminiFormatter extends GeminiChatFormatter {
    private final String provider;
    private final String model;

    NativeGeminiFormatter(String provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    @Override
    protected List<Content> doFormat(List<Msg> messages) {
        var generatedIds = new HashSet<String>();
        for (var message : messages) {
            NativeModelHistory.state(message, "gemini", provider, model).ifPresent(state -> state.items().stream()
                .filter(item -> Boolean.TRUE.equals(item.attributes().get("generated_id")))
                .map(NativeModelState.Item::toolCallId).forEach(generatedIds::add));
        }
        var result = new ArrayList<Content>();
        for (var message : messages) {
            var state = NativeModelHistory.state(message, "gemini", provider, model);
            List<Content> formatted;
            if (message.getRole() == MsgRole.ASSISTANT && state.isPresent() && !state.get().items().isEmpty()) {
                var parts = state.get().items().stream().map(item -> nativePart(message, item)).toList();
                formatted = List.of(Content.builder().role("model").parts(parts).build());
            } else {
                formatted = super.doFormat(List.of(message));
            }
            for (var content : formatted) {
                var parts = content.parts().orElse(List.of()).stream().map(part -> removeGeneratedId(part, generatedIds)).toList();
                var next = content.toBuilder().parts(parts).build();
                if (!result.isEmpty() && next.role().orElse("").equals("user")
                    && result.getLast().role().orElse("").equals("user")) {
                    var previous = result.getLast();
                    var combined = new ArrayList<>(previous.parts().orElse(List.of()));
                    combined.addAll(parts);
                    result.set(result.size() - 1, previous.toBuilder().parts(combined).build());
                } else {
                    result.add(next);
                }
            }
        }
        return List.copyOf(result);
    }

    private Part nativePart(Msg message, NativeModelState.Item item) {
        Part.Builder part;
        if (item.kind().equals("tool_use")) {
            var tool = message.getContentBlocks(ToolUseBlock.class).stream()
                .filter(value -> Objects.equals(value.getId(), item.toolCallId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("保存的模型内容缺少对应工具调用。"));
            var single = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(tool)).build();
            part = super.doFormat(List.of(single)).getFirst().parts().orElseThrow().getFirst().toBuilder();
        } else {
            part = Part.builder().text(NativeModelHistory.text(message, item));
            if (item.kind().equals("thinking")) {
                part.thought(true);
            }
        }
        Object signature = item.attributes().get("signature");
        if (signature instanceof String encoded) {
            part.thoughtSignature(Base64.getDecoder().decode(encoded));
        }
        return part.build();
    }

    private static Part removeGeneratedId(Part part, Set<String> generated) {
        if (part.functionCall().isPresent()) {
            var call = part.functionCall().orElseThrow();
            if (call.id().filter(generated::contains).isPresent()) {
                return part.toBuilder().functionCall(call.toBuilder().clearId().build()).build();
            }
        }
        if (part.functionResponse().isPresent()) {
            var response = part.functionResponse().orElseThrow();
            if (response.id().filter(generated::contains).isPresent()) {
                return part.toBuilder().functionResponse(response.toBuilder().clearId().build()).build();
            }
        }
        return part;
    }
}
