package com.stonewu.agenteam.service.modelprofile.protocol.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.stonewu.agenteam.model.modelprofile.entity.NativeModelState;
import com.stonewu.agenteam.service.modelprofile.protocol.NativeModelHistory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 复用普通消息转换，仅恢复有签名的原生助手内容，并合并并行工具的返回消息。
 */
final class NativeAnthropicFormatter extends AnthropicChatFormatter {
    private final String provider;
    private final String model;

    NativeAnthropicFormatter(String provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    @Override
    public List<MessageParam> doFormat(List<Msg> messages) {
        var result = new ArrayList<MessageParam>();
        for (var message : messages) {
            var state = NativeModelHistory.state(message, "anthropic", provider, model);
            List<MessageParam> formatted;
            if (message.getRole() == MsgRole.ASSISTANT && state.isPresent() && !state.get().items().isEmpty()) {
                var blocks = state.get().items().stream().map(item -> nativeBlock(message, item)).toList();
                formatted = List.of(MessageParam.builder().role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(blocks).build());
            } else {
                formatted = super.doFormat(List.of(message));
            }
            for (var value : formatted) {
                append(result, value);
            }
        }
        return List.copyOf(result);
    }

    private ContentBlockParam nativeBlock(Msg message, NativeModelState.Item item) {
        return switch (item.kind()) {
            case "thinking" -> ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                .thinking(NativeModelHistory.text(message, item)).signature(attribute(item, "signature")).build());
            case "redacted_thinking" -> ContentBlockParam.ofRedactedThinking(RedactedThinkingBlockParam.builder()
                .data(attribute(item, "data")).build());
            case "text" -> ContentBlockParam.ofText(TextBlockParam.builder()
                .text(NativeModelHistory.text(message, item)).build());
            case "tool_use" -> {
                var tool = message.getContentBlocks(ToolUseBlock.class).stream()
                    .filter(value -> Objects.equals(value.getId(), item.toolCallId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("保存的模型内容缺少对应工具调用。"));
                yield ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(tool.getId()).name(tool.getName())
                    .input(JsonValue.from(tool.getInput())).build());
            }
            default -> throw new IllegalStateException("保存的模型包含当前不支持的原生内容类型。");
        };
    }

    private static String attribute(NativeModelState.Item item, String name) {
        Object value = item.attributes().get(name);
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new IllegalStateException("保存的模型签名内容不完整。");
        }
        return text;
    }

    private static void append(List<MessageParam> result, MessageParam value) {
        if (!result.isEmpty() && value.role().equals(MessageParam.Role.USER)
            && result.getLast().role().equals(MessageParam.Role.USER)
            && value.content().isBlockParams() && result.getLast().content().isBlockParams()) {
            var previous = result.getLast();
            var blocks = new ArrayList<>(previous.content().asBlockParams());
            blocks.addAll(value.content().asBlockParams());
            result.set(result.size() - 1, previous.toBuilder().contentOfBlockParams(blocks).build());
        } else {
            result.add(value);
        }
    }
}
