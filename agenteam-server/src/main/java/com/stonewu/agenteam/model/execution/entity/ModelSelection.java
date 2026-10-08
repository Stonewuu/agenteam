package com.stonewu.agenteam.model.execution.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.modelprofile.entity.ReasoningEffort;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 对话明确选择的模型；思考等级为空时使用模型自身的默认行为。
 */
public record ModelSelection(
    @NotBlank(message = "请选择模型。") @Size(max = 100, message = "模型编号过长。") String modelProfileId,
    @Pattern(regexp = ReasoningEffort.PATTERN, message = "请选择有效的思考等级。") String reasoningEffort) {
    public static ModelSelection fromConfig(JsonNode config) {
        String id = config.path("modelProfileId").asText(null);
        return id == null ? null : new ModelSelection(id, config.path("reasoningEffort").asText(null));
    }
}
