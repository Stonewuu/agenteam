package com.stonewu.agenteam.model.resource.request;

import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 上架与使用范围一同保存，不改变发布配置；空范围明确表示仅所有者可使用。
 */
public record AgentListingRequest(
    @NotNull(message = "{validation.required}") Boolean listed,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:automatic|approval)", message = "{validation.option}") String hirePolicy,
    @Size(max = 500, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid ResourceGrantSpec> useGrants) {
    public AgentListingRequest(Boolean listed, String hirePolicy) {
        this(listed, hirePolicy, null);
    }
}
