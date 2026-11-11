package com.stonewu.agenteam.model.data.request;

import com.stonewu.agenteam.model.data.entity.DataField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 只配置明确的集合与字段，不接受查询语句或临时接口地址。
 */
public record DataCollectionWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String sourceName,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid DataField> fields) {
}
