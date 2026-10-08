package com.stonewu.agenteam.model.execution.response;

import com.stonewu.agenteam.model.execution.entity.ModelSelection;

import java.util.List;

/**
 * 对话使用的模型选择信息，不包含提供方地址、凭据或智能体内部指令。
 */
public record ConversationModelOptions(boolean configurable, ModelSelection defaultSelection,
                                       ModelSelection selection, List<Option> models) {
    public record Option(String id, String name, String modelName, List<String> reasoningEfforts,
                         boolean available, String unavailableReason) {
    }
}
