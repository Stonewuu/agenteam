package com.stonewu.agenteam.model.modelprofile.entity;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理员明确填写的模型能力，不通过模型名称推断。
 */
public record ModelCapabilities(
    @NotNull(message = "{validation.required}") Boolean supportsTools,
    @NotNull(message = "{validation.required}") Boolean supportsTemperature,
    @NotNull(message = "{validation.required}") @Min(value = 128L, message = "{validation.range}") @Max(value = 1000000L, message = "{validation.range}") Integer maxOutputTokens,
    @NotNull(message = "{validation.required}") @Min(value = 128L, message = "{validation.range}") @Max(value = 10000000L, message = "{validation.range}") Integer maxContextTokens,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 2, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:text|image)", message = "{validation.option}") String> inputTypes,
    @Size(max = 7, message = "{error.modelprofile.modelCapabilities.reasoningEfforts.1}") @UniqueElements(message = "{error.modelprofile.modelCapabilities.reasoningEfforts.2}")
    List<@NotNull(message = "{validation.modelprofile.modelCapabilities.reasoningEfforts.1}") @Pattern(regexp = ReasoningEffort.PATTERN, message = "{validation.modelprofile.modelCapabilities.reasoningEfforts.2}") String> reasoningEfforts,
    @Valid ModelProtocolOptions protocolOptions) {
    public ModelCapabilities {
        reasoningEfforts = reasoningEfforts == null ? List.of() : Collections.unmodifiableList(
            new ArrayList<>(reasoningEfforts));
        protocolOptions = protocolOptions == null ? ModelProtocolOptions.defaults() : protocolOptions;
    }

    public ModelCapabilities(Boolean supportsTools, Boolean supportsTemperature, Integer maxOutputTokens,
                             Integer maxContextTokens, List<String> inputTypes, List<String> reasoningEfforts) {
        this(supportsTools, supportsTemperature, maxOutputTokens, maxContextTokens, inputTypes, reasoningEfforts, null);
    }

    public ModelCapabilities(Boolean supportsTools, Boolean supportsTemperature, Integer maxOutputTokens,
                             Integer maxContextTokens, List<String> inputTypes) {
        this(supportsTools, supportsTemperature, maxOutputTokens, maxContextTokens, inputTypes, List.of());
    }

}
