package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.workspace.ActivityTrendSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.workspace.response.ActivityTrendView;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只查询本人活动；按时区偏移变化分段，夏令时切换也不会多算或漏算一天。
 */
@Service
public class ActivityTrendService {
    private final EnterpriseAuthorizationService access;
    private final EnterpriseMapper enterprises;
    private final ActivityTrendSqlMapper activities;
    private final Clock clock;

    public ActivityTrendService(EnterpriseAuthorizationService access, EnterpriseMapper enterprises,
                                ActivityTrendSqlMapper activities, Clock clock) {
        this.access = access;
        this.enterprises = enterprises;
        this.activities = activities;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityTrendView get(AuthContext actor) {
        access.require(actor, "workspace.view");
        ZoneId zone = ZoneId.of(enterprises.timezone(actor.enterpriseId()));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate first = today.minusYears(1).plusDays(1);
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        Instant start = first.atStartOfDay(zone).toInstant();
        while (start.isBefore(end)) {
            var transition = zone.getRules().nextTransition(start);
            Instant segmentEnd = transition != null && transition.getInstant()
                .isBefore(end) ? transition.getInstant() : end;
            int offset = zone.getRules().getOffset(start).getTotalSeconds();
            for (var row : activities.countActivities(actor.enterpriseId(), actor.userId(), start, segmentEnd,
                offset)) {
                counts.computeIfAbsent(row.getDate(), ignored -> new LinkedHashMap<>())
                    .merge(row.getCategory(), row.getCount(), Long::sum);
            }
            start = segmentEnd;
        }
        var days = new ArrayList<ActivityTrendView.Day>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            var count = counts.getOrDefault(day.toString(), Map.of());
            days.add(new ActivityTrendView.Day(day.toString(), count.getOrDefault("conversations", 0L),
                count.getOrDefault("schedules", 0L), count.getOrDefault("todos", 0L),
                count.getOrDefault("employees", 0L)));
        }
        return new ActivityTrendView(first.toString(), today.toString(), zone.getId(), days);
    }
}
