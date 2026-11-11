package com.stonewu.agenteam.mapper.schedule;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.schedule.entity.ScheduleRule;
import com.stonewu.agenteam.model.schedule.entity.ScheduleTime;
import com.stonewu.agenteam.model.schedule.response.ScheduleTimesView;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 只提示实际计算到的本地时间变化，不创造没有依据的运行状态。
 */
@Component
public class ScheduleTimesMapper {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    public ScheduleTimesView view(ScheduleRule rule, List<ScheduleTime> times) {
        var warnings = new ArrayList<String>();
        for (var time : times) {
            String warning = switch (time.adjustment()) {
                case NONE -> null;
                case SHIFTED_FORWARD ->
                    Messages.text("text.schedule.scheduleTimesMapper.view.1", LOCAL.format(time.requested()), LOCAL.format(
                        time.actual()));
                case FIRST_OCCURRENCE ->
                    Messages.text("text.schedule.scheduleTimesMapper.view.2", LOCAL.format(time.requested()));
            };
            if (warning != null && !warnings.contains(warning)) {
                warnings.add(warning);
            }
        }
        if (times.isEmpty()) {
            warnings.add(Messages.text("text.schedule.scheduleTimesMapper.view.3"));
        }
        return new ScheduleTimesView(rule.timezone().getId(),
            times.stream().map(time -> time.instant().toString()).toList(), List.copyOf(warnings));
    }
}
