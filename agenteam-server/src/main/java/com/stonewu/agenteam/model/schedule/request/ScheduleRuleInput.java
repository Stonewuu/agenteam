package com.stonewu.agenteam.model.schedule.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 时间预览仅接收时间规则，不创建计划或执行。
 */
public record ScheduleRuleInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:once|daily|weekly|monthly)", message = "请选择支持的选项或格式。") String frequency,
    @JsonProperty(value = "localDate", required = true) @Size(min = 10, max = 10, message = "文字长度不符合要求。") String localDate,
    @NotNull(message = "请填写必填项。") @Size(min = 5, max = 5, message = "文字长度不符合要求。") @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "请选择支持的选项或格式。") String localTime,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 7, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 7L, message = "数值超出允许范围。") Integer> weekdays,
    @JsonProperty(value = "monthDay", required = true) @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 31L, message = "数值超出允许范围。") Integer monthDay,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") String timezone) {
}
