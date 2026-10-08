package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stonewu.agenteam.model.schedule.request.ScheduleActionRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 部署关闭通知计划后，服务端也必须拒绝绕过目录直接提交的新操作。 */
@TestPropertySource(properties = "agenteam.schedule.notification-actions-enabled=false")
@Import(SharedEnterpriseTestEdition.class)
class ScheduleNotificationRolloutTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void disabledNotificationsAreAbsentFromTheCatalogAndRejectedByTheWriteEndpoint() throws Exception {
        var available = data(mvc.perform(get(base() + "/schedule-actions").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(1, available.size());
        assertEquals("agent.run", available.get(0).path("type").asText());
        var input = new ScheduleWriteRequest("暂不开放的通知", null, null, null, "daily", null, "09:15", List.of(), null, "UTC", false, 0,
            new ScheduleActionRequest("notification.send", 1, Map.of("title", "不应创建", "body", "不应发送",
                "recipients", List.of(Map.of("userId", admin, "connectionIds", List.of())))));
        var result = write(base() + "/schedules", input, UUID.randomUUID().toString())
            .andExpect(status().isConflict()).andReturn();
        assertEquals("SCHEDULE_NOTIFICATIONS_DISABLED", json.readTree(result.getResponse().getContentAsString()).at("/error/code").asText());
        assertEquals(0, count("scheduled_task"));
        assertEquals(0, count("notification"));
    }
}
