package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.workspace.ActivityTrendSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.workspace.entity.ActivityCountRow;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActivityTrendServiceTest {
    @Test
    void timezoneTransitionsAreContinuousAndEventsAroundMidnightBelongToTheCorrectDay() {
        var access = mock(EnterpriseAuthorizationService.class);
        var enterprises = mock(EnterpriseMapper.class);
        var mapper = mock(ActivityTrendSqlMapper.class);
        var actor = mock(AuthContext.class);
        when(actor.enterpriseId()).thenReturn("enterprise");
        when(actor.userId()).thenReturn("user");
        String timezone = "America/Los_Angeles";
        when(enterprises.timezone("enterprise")).thenReturn(timezone);
        var clock = Clock.fixed(Instant.parse("2024-11-05T10:00:00Z"), ZoneOffset.UTC);
        List<Instant> events = List.of(Instant.parse("2024-03-10T07:59:59Z"), Instant.parse("2024-03-10T08:00:00Z"),
            Instant.parse("2024-03-10T10:00:00Z"), Instant.parse("2024-11-03T08:30:00Z"), Instant.parse("2024-11-03T09:30:00Z"));
        var boundaries = new ArrayList<Instant>();
        when(mapper.countActivities(eq("enterprise"), eq("user"), any(), any(), anyInt())).thenAnswer(call -> {
            Instant start = call.getArgument(2);
            Instant end = call.getArgument(3);
            int offset = call.getArgument(4);
            if (!boundaries.isEmpty()) {
                assertEquals(boundaries.getLast(), start);
            }
            boundaries.add(end);
            assertTrue(start.isBefore(end));
            return events.stream().filter(time -> !time.isBefore(start) && time.isBefore(end)).map(time -> {
                var row = new ActivityCountRow();
                row.setDate(time.atOffset(ZoneOffset.ofTotalSeconds(offset)).toLocalDate().toString());
                row.setCategory("conversations");
                row.setCount(1);
                return row;
            }).toList();
        });
        var result = new ActivityTrendService(access, enterprises, mapper, clock).get(actor);
        assertEquals(366, result.days().size());
        assertEquals("2023-11-06", result.startDate());
        assertEquals("2024-11-05", result.endDate());
        assertEquals(3, boundaries.size());
        assertEquals(LocalDate.parse("2024-11-06").atStartOfDay(ZoneId.of(timezone)).toInstant(), boundaries.getLast());
        assertEquals(5, result.days().stream().mapToLong(day -> day.total()).sum());
        assertEquals(1, result.days().stream().filter(day -> day.date().equals("2024-03-09")).findFirst().orElseThrow().conversations());
        assertEquals(2, result.days().stream().filter(day -> day.date().equals("2024-03-10")).findFirst().orElseThrow().conversations());
        assertEquals(2, result.days().stream().filter(day -> day.date().equals("2024-11-03")).findFirst().orElseThrow().conversations());
        verify(access).require(actor, "workspace.view");
    }

    @Test
    void fractionalTimezoneOffsetsAndEmptyDaysDoNotDependOnTheServerTimezone() {
        var access = mock(EnterpriseAuthorizationService.class);
        var enterprises = mock(EnterpriseMapper.class);
        var mapper = mock(ActivityTrendSqlMapper.class);
        var actor = mock(AuthContext.class);
        when(actor.enterpriseId()).thenReturn("enterprise");
        when(actor.userId()).thenReturn("user");
        when(enterprises.timezone("enterprise")).thenReturn("Asia/Kathmandu");
        var clock = Clock.fixed(Instant.parse("2026-09-22T18:15:00Z"), ZoneOffset.UTC);
        var result = new ActivityTrendService(access, enterprises, mapper, clock).get(actor);
        assertEquals("2026-09-23", result.endDate());
        assertEquals(365, result.days().size());
        assertTrue(result.days().stream().allMatch(day -> day.total() == 0));
        verify(mapper).countActivities("enterprise", "user", Instant.parse("2025-09-23T18:15:00Z"),
            Instant.parse("2026-09-23T18:15:00Z"), 20700);
    }
}
