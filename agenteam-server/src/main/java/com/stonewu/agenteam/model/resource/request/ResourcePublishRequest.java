package com.stonewu.agenteam.model.resource.request;

import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 省略授权或上架信息时保持原值；提交时与发布一起验证和保存。
 */
public record ResourcePublishRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 500, message = "{validation.textLength}") String releaseNote,
    @Size(min = 0, max = 500, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid ResourceGrantSpec> grants,
    @Valid AgentListingRequest listing) {
}
