package com.stonewu.agenteam.model.resource.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.Map;

/**
 * 创建仅接收草稿内容，不接受所有者、来源与发布状态。
 */
public record ResourceCreateRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:agent|skill|plugin|workflow|knowledge|data)", message = "{validation.option}") String kind,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 500, message = "{validation.textLength}") String description,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> tagIds,
    @NotNull(message = "{validation.required}") Map<String, Object> config) {
}
