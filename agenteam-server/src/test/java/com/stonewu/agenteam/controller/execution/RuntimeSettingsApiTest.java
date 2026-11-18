package com.stonewu.agenteam.controller.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.configuration.system.RuntimeSettings;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.TestOutputPaths;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过管理接口修改后，验证任务接收、同会话排队及等待超时使用新设置。 */
@Import(SharedEnterpriseTestEdition.class)
class RuntimeSettingsApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private RuntimeSettings settings;
    @Autowired
    private RuntimeSettingsStore runtimeStore;
    @Autowired
    private RunMapper runs;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.user-workspaces-root", () -> TestOutputPaths.path("runtime-settings-api/workspaces").toString());
    }

    @AfterEach
    void resetSettings() {
        var saved = runtimeStore.current();
        if (saved != null) {
            runtimeStore.save(saved.revision(), Map.of());
        }
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void unlimitedAdmissionAndQueueTimeApplyToSavedTasks() throws Exception {
        String endpoint = "/api/v1/system/runtime-settings";
        var current = data(mvc.perform(get(endpoint).cookie(cookie)).andExpect(status().isOk()).andReturn());
        var updated = data(change(HttpMethod.PUT, endpoint + "/execution", Map.of("values", Map.of(
            "workerConcurrent", 1000, "enterpriseActiveRuns", -1, "userActiveRuns", -1,
            "conversationQueuedMessages", -1, "queueTimeoutSeconds", -1)), current.path("revision").asText(), UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn());
        assertEquals(1000, updated.path("values").path("workerConcurrent").asInt());
        assertEquals(-1, updated.path("values").path("queueTimeoutSeconds").asInt());
        for (int index = 0; index < 22; index++) {
            write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        }
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        for (int index = 0; index < 22; index++) {
            write(base() + "/conversations/" + accepted.path("conversationId").asText() + "/messages?queue=true",
                input("等待消息 " + index), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        }
        String run = accepted.path("runId").asText();
        databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>()
            .eq(AgentRunRow::getId, run).set(AgentRunRow::getQueuedAt, Instant.EPOCH));
        assertTrue(lifecycle.queueTimeouts().isEmpty());
        assertFalse(settings.queueExpired(Instant.EPOCH, Instant.now()));
        assertEquals(callsBefore, modelCalls.get());
    }

    @Test
    void savedLimitsControlNewAndAlreadyQueuedTasksWithoutRestart() throws Exception {
        String endpoint = "/api/v1/system/runtime-settings";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        var current = data(mvc.perform(get(endpoint).cookie(cookie)).andExpect(status().isOk()).andReturn());
        var revision = current.path("revision").asText();
        var updated = data(change(HttpMethod.PUT, endpoint + "/execution", Map.of("values", Map.of(
            "workerConcurrent", 2, "enterpriseActiveRuns", 2, "userActiveRuns", 1,
            "conversationQueuedMessages", 1, "queueTimeoutSeconds", 60)), revision, UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn());
        assertEquals(2, settings.value(RuntimeSetting.WORKER_CONCURRENT));
        change(HttpMethod.PUT, endpoint + "/execution", Map.of("values", Map.of("workerConcurrent", 3)),
            revision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        change(HttpMethod.PUT, endpoint + "/model", Map.of("values", Map.of("toolReadKb", 999)),
            updated.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());

        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        var denied = write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isConflict()).andReturn();
        assertTrue(denied.getResponse().getContentAsString().contains("CONCURRENCY_LIMIT"));
        String conversation = accepted.path("conversationId").asText();
        var waiting = data(write(base() + "/conversations/" + conversation + "/messages?queue=true", input("下一条消息"),
            UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertEquals("waiting_turn", waiting.path("status").asText());
        var full = write(base() + "/conversations/" + conversation + "/messages?queue=true", input("再追加一条"),
            UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
        assertTrue(full.getResponse().getContentAsString().contains("CONVERSATION_QUEUE_FULL"));

        String run = accepted.path("runId").asText();
        assertEquals(1, databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>()
            .eq(AgentRunRow::getId, run).set(AgentRunRow::getQueuedAt, Instant.now().minusSeconds(10))));
        change(HttpMethod.PUT, endpoint + "/execution", Map.of("values", Map.of("queueTimeoutSeconds", 5)),
            updated.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        var lease = lifecycle.claim("runtime-settings-test").orElseThrow();
        assertEquals(run, lease.runId());
        assertTrue(lifecycle.start(lease).isEmpty());
        assertEquals("EXECUTION_QUEUE_TIMEOUT", runs.find(enterprise, run, false).orElseThrow().errorCode());
        assertEquals(callsBefore, modelCalls.get());
    }
}
