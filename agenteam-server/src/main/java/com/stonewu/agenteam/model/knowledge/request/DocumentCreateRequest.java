package com.stonewu.agenteam.model.knowledge.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 一次加入一至十份已经上传并通过检查的资料。
 */
public record DocumentCreateRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> fileIds) {
}
