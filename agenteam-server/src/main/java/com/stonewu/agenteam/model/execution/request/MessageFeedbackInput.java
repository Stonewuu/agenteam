package com.stonewu.agenteam.model.execution.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 空反馈值表示撤回，说明只保存在本人反馈记录中。
 */
public record MessageFeedbackInput(
    @JsonProperty(value = "value", required = true) @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:positive|negative)", message = "请选择支持的选项或格式。") String value,
    @Size(min = 0, max = 500, message = "文字长度不符合要求。") String comment) {
}
