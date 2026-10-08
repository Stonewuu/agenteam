package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 时间预览使用正式请求、当前权限和固定时钟，不创建执行或用量。
 */
@Import({SharedEnterpriseTestEdition.class, ScheduleTimeApiTest.TimeConfiguration.class})
class ScheduleTimeApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        Clock schedulePreviewClock() {
            return Clock.fixed(Instant.parse("2026-03-08T06:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void previewReturnsActualInstantsAndOnlyRealAdjustmentsWithoutSubmittingWork() throws Exception {
        var result = data(write(path(), rule("daily", null, "02:30", List.of(), null, "America/New_York"), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        schemas.validate("ScheduleTimes", result);
        assertEquals(5, result.path("times").size());
        assertEquals("2026-03-08T07:30:00Z", result.at("/times/0").asText());
        assertEquals("2026-03-09T06:30:00Z", result.at("/times/1").asText());
        assertEquals(1, result.path("warnings").size());
        assertTrue(result.at("/warnings/0").asText().contains("03:30"));
        assertEquals(0, count("agent_run"));
        assertEquals(0, count("quota_entry"));
        assertEquals(callsBefore, modelCalls.get());
    }

    @Test
    void aPastOneTimeChoiceReturnsNoFutureDatesAndMalformedRulesAreRejected() throws Exception {
        var past = data(write(path(), rule("once", "2026-03-07", "02:30", List.of(), null, "UTC"), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertTrue(past.path("times").isEmpty());
        assertEquals(1, past.path("warnings").size());
        for (var invalid : List.of(rule("weekly", null, "09:00", List.of(), null, "UTC"), rule("daily", null, "09:00:01", List.of(), null, "UTC"),
            rule("monthly", null, "09:00", List.of(), 32, "UTC"), rule("once", "2026-02-29", "09:00", List.of(), null, "UTC"),
            rule("daily", null, "09:00", List.of(), null, "Invalid/Zone"))) {
            write(path(), invalid, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        }
        var unknown = rule("daily", null, "09:00", List.of(), null, "UTC");
        unknown.put("serverTimezone", "UTC");
        var rejected = write(path(), unknown, UUID.randomUUID().toString()).andExpect(status().isBadRequest()).andReturn();
        assertEquals("VALIDATION_FAILED", json.readTree(rejected.getResponse().getContentAsString()).at("/error/code").asText());
    }

    @Test
    void previewStillRequiresLoginAndRequestForgeryProtection() throws Exception {
        var body = json.writeValueAsString(rule("daily", null, "09:00", List.of(), null, "UTC"));
        mvc.perform(post(path()).cookie(cookie).header("Origin", "http://localhost:3000").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path()).header("Origin", "http://localhost:3000").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    private String path() {
        return base() + "/schedules/preview-times";
    }

    private Map<String, Object> rule(String frequency, String date, String time, List<Integer> weekdays, Integer monthDay, String zone) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("frequency", frequency);
        result.put("localDate", date);
        result.put("localTime", time);
        result.put("weekdays", weekdays);
        result.put("monthDay", monthDay);
        result.put("timezone", zone);
        return result;
    }
}
