package com.stonewu.agenteam.mapper.schedule;

import com.stonewu.agenteam.model.schedule.entity.ScheduleRule;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRule.Frequency;
import com.stonewu.agenteam.model.schedule.request.ScheduleRuleInput;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.auth.AccountInputValidation;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 把已通过请求结构校验的日期和时区转换为明确的时间规则。
 */
@Component
public class ScheduleRuleMapper {
    public ScheduleRule from(ScheduleWriteRequest input) {
        return from(new ScheduleRuleInput(input.frequency(), input.localDate(), input.localTime(), input.weekdays(),
            input.monthDay(), input.timezone()));
    }

    public ScheduleRule from(ScheduleRuleInput input) {
        LocalDate date;
        try {
            date = input.localDate() == null ? null : LocalDate.parse(input.localDate());
        } catch (DateTimeException invalid) {
            throw ApiException.invalidField("localDate", "请选择有效的执行日期。");
        }
        LocalTime time;
        try {
            time = LocalTime.parse(input.localTime());
        } catch (DateTimeException invalid) {
            throw ApiException.invalidField("localTime", "请选择精确到分钟的执行时间。");
        }
        var zone = ZoneId.of(AccountInputValidation.timezone(input.timezone()));
        try {
            var days = input.weekdays().stream().map(DayOfWeek::of).collect(Collectors.toSet());
            return new ScheduleRule(Frequency.valueOf(input.frequency().toUpperCase(Locale.ROOT)), date, time, days,
                input.monthDay(), zone);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw ApiException.invalidField("frequency", "日期、星期和月日需要符合所选重复方式。");
        }
    }
}
