package com.stonewu.agenteam.model.execution.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 空反馈值表示撤回，说明只保存在本人反馈记录中。
 */
public record MessageFeedbackInput(
    @JsonProperty(value = "value", required = true) @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:positive|negative)", message = "{validation.option}") String value,
    @Size(min = 0, max = 500, message = "{validation.textLength}") String comment) {
}
