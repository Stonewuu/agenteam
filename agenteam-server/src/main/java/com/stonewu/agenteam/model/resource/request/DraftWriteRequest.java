package com.stonewu.agenteam.model.resource.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.Map;

/**
 * 完整替换草稿，修改版本由请求头单独提供。
 */
public record DraftWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 500, message = "{validation.textLength}") String description,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> tagIds,
    @NotNull(message = "{validation.required}") Map<String, Object> config) {
}
