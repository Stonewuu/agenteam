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
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String hireId,
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String agentVersionId,
    @Size(min = 1, max = 20000, message = "文字长度不符合要求。") String inputText,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:once|daily|weekly|monthly)", message = "请选择支持的选项或格式。") String frequency,
    @JsonProperty(value = "localDate", required = true) @Size(min = 10, max = 10, message = "文字长度不符合要求。") String localDate,
    @NotNull(message = "请填写必填项。") @Size(min = 5, max = 5, message = "文字长度不符合要求。") @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "请选择支持的选项或格式。") String localTime,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 7, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 7L, message = "数值超出允许范围。") Integer> weekdays,
    @JsonProperty(value = "monthDay", required = true) @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 31L, message = "数值超出允许范围。") Integer monthDay,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") String timezone,
    @JsonProperty(value = "enabled", required = true) boolean enabled,
    @JsonProperty(value = "maxRetries", required = true) @Min(value = 0L, message = "数值超出允许范围。") @Max(value = 2L, message = "数值超出允许范围。") int maxRetries,
    @Valid ScheduleActionRequest action) {
    public ScheduleWriteRequest(String name, String hireId, String agentVersionId, String inputText, String frequency,
                                 String localDate, String localTime, List<Integer> weekdays, Integer monthDay, String timezone,
                                 boolean enabled, int maxRetries) {
        this(name, hireId, agentVersionId, inputText, frequency, localDate, localTime, weekdays, monthDay, timezone, enabled, maxRetries, null);
    }
}
