package com.stonewu.agenteam.model.data.request;

import com.stonewu.agenteam.model.data.entity.DataField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 确认预览及字段定义后，完整验证每一行才能提交新版本。
 */
public record DataImportConfirmRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 8192, message = "{validation.textLength}") String previewToken,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid DataField> fields) {
}
