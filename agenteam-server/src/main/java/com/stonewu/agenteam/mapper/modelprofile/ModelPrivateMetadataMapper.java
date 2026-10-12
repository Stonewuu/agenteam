package com.stonewu.agenteam.mapper.modelprofile;

import com.stonewu.agenteam.model.modelprofile.entity.ModelHistoryOrigin;
import com.stonewu.agenteam.model.modelprofile.entity.NativeModelState;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 协议续接所需的私有字段不能进入可见事件，也不能跨模型回传。
 */
public final class ModelPrivateMetadataMapper {
    private static final Set<String> PRIVATE_KEYS = Set.of(NativeModelState.METADATA_KEY,
        ModelHistoryOrigin.METADATA_KEY, ThinkingBlock.METADATA_REASONING_DETAILS,
        "openai.reasoning.encrypted_content", "openai.reasoning.text", "openai.response.error",
        ToolUseBlock.METADATA_THOUGHT_SIGNATURE);

    private ModelPrivateMetadataMapper() {
    }

    public static Map<String, Object> removePrivate(Map<?, ?> metadata) {
        var result = new LinkedHashMap<String, Object>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (!PRIVATE_KEYS.contains(String.valueOf(key))) {
                    result.put(String.valueOf(key), value);
                }
            });
        }
        return result;
    }
}
