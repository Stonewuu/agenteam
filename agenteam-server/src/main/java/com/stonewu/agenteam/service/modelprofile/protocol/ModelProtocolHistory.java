package com.stonewu.agenteam.service.modelprofile.protocol;

import com.stonewu.agenteam.mapper.modelprofile.ModelPrivateMetadataMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelHistoryOrigin;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.util.JsonUtils;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 对同一模型保留可恢复状态，切换模型时只传递可通用的对话内容。
 */
public final class ModelProtocolHistory {
    private final ModelHistoryOrigin origin;

    public ModelProtocolHistory(String protocol, String provider, String model) {
        this.origin = new ModelHistoryOrigin(protocol, provider, model);
    }

    public List<Msg> prepare(List<Msg> messages) {
        return messages.stream().map(message -> {
            if (owned(message)) {
                return message;
            }
            return message.withContent(message.getContent().stream().map(ModelProtocolHistory::withoutSignature).toList())
                .withMetadata(ModelPrivateMetadataMapper.removePrivate(message.getMetadata()));
        }).toList();
    }

    public ChatResponse identify(ChatResponse response) {
        var metadata = new LinkedHashMap<String, Object>();
        if (response.getMetadata() != null) {
            metadata.putAll(response.getMetadata());
        }
        metadata.put(ModelHistoryOrigin.METADATA_KEY, origin);
        return ChatResponse.builder().id(response.getId()).content(response.getContent()).usage(response.getUsage())
            .metadata(metadata).finishReason(response.getFinishReason()).build();
    }

    private boolean owned(Msg message) {
        Object value = message.getMetadata() == null ? null : message.getMetadata().get(ModelHistoryOrigin.METADATA_KEY);
        if (value != null) {
            var previous = value instanceof ModelHistoryOrigin record ? record
                : JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(value), ModelHistoryOrigin.class);
            return origin.equals(previous);
        }
        return NativeModelHistory.state(message, origin.protocol(), origin.providerId(), origin.modelName()).isPresent();
    }

    private static ContentBlock withoutSignature(ContentBlock block) {
        if (block instanceof ThinkingBlock thinking) {
            return ThinkingBlock.builder().thinking(thinking.getThinking())
                .metadata(ModelPrivateMetadataMapper.removePrivate(thinking.getMetadata())).build();
        }
        if (block instanceof ToolUseBlock tool) {
            return ToolUseBlock.builder().id(tool.getId()).name(tool.getName()).input(tool.getInput())
                .content(tool.getContent()).state(tool.getState())
                .metadata(ModelPrivateMetadataMapper.removePrivate(tool.getMetadata())).build();
        }
        return block;
    }
}
