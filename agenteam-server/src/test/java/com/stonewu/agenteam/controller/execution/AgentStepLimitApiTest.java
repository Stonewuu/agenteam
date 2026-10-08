package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.stonewu.agenteam.mapper.execution.RunActivityMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SharedEnterpriseTestEdition.class)
class AgentStepLimitApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Autowired
    private RunActivityMapper activity;

    @ParameterizedTest
    @ValueSource(ints = {0, 500, 20})
    void savedLimitsArePublishedAndAppliedWhenReservingExecutionSteps(int maxSteps) throws Exception {
        configureAgent(config -> config.put("maxSteps", maxSteps));
        write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        var lease = lifecycle.claim("步骤限制测试").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        assertEquals(maxSteps, run.executionConfig().at("/config/maxSteps").asInt());
        int first = maxSteps == 0 ? 501 : maxSteps;
        lifecycle.reserveSteps(lease, List.of(), first, "model");
        if (maxSteps == 0) {
            lifecycle.reserveSteps(lease, List.of(), 501, "model");
            assertEquals(1002, activity.get(run).usedSteps());
        } else {
            assertEquals("EXECUTION_STEP_LIMIT", assertThrows(ApiException.class,
                () -> lifecycle.reserveSteps(lease, List.of(), 1, "model")).code());
            assertEquals(maxSteps, activity.get(run).usedSteps());
        }
    }
}
