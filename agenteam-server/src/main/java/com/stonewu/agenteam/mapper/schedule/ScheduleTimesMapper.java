package com.stonewu.agenteam.mapper.schedule;

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
                    "所选本地时间 " + LOCAL.format(time.requested()) + " 不存在，该次将在 " + LOCAL.format(
                        time.actual()) + " 执行。";
                case FIRST_OCCURRENCE ->
                    "本地时间 " + LOCAL.format(time.requested()) + " 会出现两次，该次只在第一次出现时执行。";
            };
            if (warning != null && !warnings.contains(warning)) {
                warnings.add(warning);
            }
        }
        if (times.isEmpty()) {
            warnings.add("所选执行时间已经过去，请选择未来时间。");
        }
        return new ScheduleTimesView(rule.timezone().getId(),
            times.stream().map(time -> time.instant().toString()).toList(), List.copyOf(warnings));
    }
}
