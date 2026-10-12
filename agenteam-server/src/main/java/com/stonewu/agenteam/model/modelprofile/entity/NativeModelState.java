package com.stonewu.agenteam.model.modelprofile.entity;

import java.util.List;
import java.util.Map;

/**
 * 仅随框架的加密消息状态保存，普通事件和日志必须排除此字段。
 * 文字使用位置引用，避免再次保存整个模型回复；属性仅保存协议续接必需的信息。
 */
public record NativeModelState(int formatVersion, String protocol, String providerId, String modelName,
                               List<Item> items) {
    public static final String METADATA_KEY = "agenteam_native_model_state";

    public NativeModelState {
        items = List.copyOf(items);
    }

    public boolean matches(String expectedProtocol, String expectedProvider, String expectedModel) {
        return protocol.equals(expectedProtocol) && providerId.equals(expectedProvider) && modelName.equals(expectedModel);
    }

    public record Item(String kind, List<Span> spans, String toolCallId, Map<String, Object> attributes) {
        public Item {
            spans = List.copyOf(spans);
            attributes = Map.copyOf(attributes);
        }
    }

    public record Span(int start, int length) {
        public Span {
            if (start < 0 || length < 0) {
                throw new IllegalArgumentException("模型历史文字的位置不能为负数。");
            }
        }
    }
}
