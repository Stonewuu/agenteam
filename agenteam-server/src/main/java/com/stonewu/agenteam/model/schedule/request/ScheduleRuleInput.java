package com.stonewu.agenteam.model.schedule.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 时间预览仅接收时间规则，不创建计划或执行。
 */
public record ScheduleRuleInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:once|daily|weekly|monthly)", message = "{validation.option}") String frequency,
    @JsonProperty(value = "localDate", required = true) @Size(min = 10, max = 10, message = "{validation.textLength}") String localDate,
    @NotNull(message = "{validation.required}") @Size(min = 5, max = 5, message = "{validation.textLength}") @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "{validation.option}") String localTime,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 7, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Min(value = 1L, message = "{validation.range}") @Max(value = 7L, message = "{validation.range}") Integer> weekdays,
    @JsonProperty(value = "monthDay", required = true) @Min(value = 1L, message = "{validation.range}") @Max(value = 31L, message = "{validation.range}") Integer monthDay,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 64, message = "{validation.textLength}") String timezone) {
}
