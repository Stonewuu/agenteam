package com.stonewu.agenteam.model.schedule.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 用户明确选择的员工、内容和未来时间；不接收创建人或运行状态。
 */
public record ScheduleWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String hireId,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String agentVersionId,
    @Size(min = 1, max = 20000, message = "{validation.textLength}") String inputText,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:once|daily|weekly|monthly)", message = "{validation.option}") String frequency,
    @JsonProperty(value = "localDate", required = true) @Size(min = 10, max = 10, message = "{validation.textLength}") String localDate,
    @NotNull(message = "{validation.required}") @Size(min = 5, max = 5, message = "{validation.textLength}") @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "{validation.option}") String localTime,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 7, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Min(value = 1L, message = "{validation.range}") @Max(value = 7L, message = "{validation.range}") Integer> weekdays,
    @JsonProperty(value = "monthDay", required = true) @Min(value = 1L, message = "{validation.range}") @Max(value = 31L, message = "{validation.range}") Integer monthDay,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 64, message = "{validation.textLength}") String timezone,
    @JsonProperty(value = "enabled", required = true) boolean enabled,
    @JsonProperty(value = "maxRetries", required = true) @Min(value = 0L, message = "{validation.range}") @Max(value = 2L, message = "{validation.range}") int maxRetries,
    @Valid ScheduleActionRequest action) {
    public ScheduleWriteRequest(String name, String hireId, String agentVersionId, String inputText, String frequency,
                                 String localDate, String localTime, List<Integer> weekdays, Integer monthDay, String timezone,
                                 boolean enabled, int maxRetries) {
        this(name, hireId, agentVersionId, inputText, frequency, localDate, localTime, weekdays, monthDay, timezone, enabled, maxRetries, null);
    }
}
