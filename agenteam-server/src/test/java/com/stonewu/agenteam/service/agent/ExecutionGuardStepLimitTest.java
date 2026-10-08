package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionGuardStepLimitTest {
    @Test
    void unlimitedExecutionsPassFiftyStepsAndExplicitLimitsStillStop() {
        var context = RuntimeContext.builder().userId("user").sessionId("session").build();
        var model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(100_000);
        var input = new ModelCallInput(List.of(), List.of(), GenerateOptions.builder().build(), model);
        var unlimited = new ExecutionGuardMiddleware(mock(RunLifecycleService.class), null, "session", 0);
        for (int step = 0; step < 60; step++) {
            unlimited.onModelCall(null, context, input, ignored -> Flux.empty()).blockLast(Duration.ofSeconds(2));
        }
        var limited = new ExecutionGuardMiddleware(mock(RunLifecycleService.class), null, "session", 2);
        for (int step = 0; step < 2; step++) {
            limited.onModelCall(null, context, input, ignored -> Flux.empty()).blockLast(Duration.ofSeconds(2));
        }
        assertEquals("EXECUTION_STEP_LIMIT", assertThrows(ApiException.class,
            () -> limited.onModelCall(null, context, input, ignored -> Flux.empty()).blockLast(Duration.ofSeconds(2))).code());
    }
}
