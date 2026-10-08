package com.stonewu.agenteam.model.workflow.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/**
 * 本次明确提交的流程草稿与结构化测试输入，不覆盖资源草稿。
 */
public record WorkflowPreviewInput(
    @NotNull(message = "请填写必填项。") JsonNode draft,
    @NotNull(message = "请填写必填项。") JsonNode input) {
}
