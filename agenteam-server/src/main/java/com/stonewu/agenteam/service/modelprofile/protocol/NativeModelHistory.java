package com.stonewu.agenteam.service.modelprofile.protocol;

import com.stonewu.agenteam.model.modelprofile.entity.NativeModelState;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.util.JsonUtils;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 从恢复后的消息中读取协议状态，签名只能回传给生成它的同一模型配置。
 */
public final class NativeModelHistory {
    private NativeModelHistory() {
    }

    public static Optional<NativeModelState> state(Msg message, String protocol, String provider, String model) {
        Object raw = message.getMetadata() == null ? null : message.getMetadata().get(NativeModelState.METADATA_KEY);
        if (raw == null) {
            return Optional.empty();
        }
        var state = raw instanceof NativeModelState value ? value
            : JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(raw), NativeModelState.class);
        if (!state.matches(protocol, provider, model)) {
            return Optional.empty();
        }
        if (state.formatVersion() != 1) {
            throw new IllegalStateException("保存的模型状态版本不受支持。");
        }
        return Optional.of(state);
    }

    public static String text(Msg message, NativeModelState.Item item) {
        String content = item.kind().equals("thinking")
            ? message.getContentBlocks(ThinkingBlock.class).stream().map(ThinkingBlock::getThinking)
                .collect(Collectors.joining())
            : message.getContentBlocks(TextBlock.class).stream().map(TextBlock::getText).collect(Collectors.joining());
        var result = new StringBuilder();
        for (var span : item.spans()) {
            long end = (long) span.start() + span.length();
            if (end > content.length()) {
                throw new IllegalStateException("保存的模型签名与对应内容不完整，不能继续发送。");
            }
            result.append(content, span.start(), (int) end);
        }
        return result.toString();
    }
}
