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
    void unlimitedAndConfiguredStepBudgetsRemainIndependent() {
        var context = RuntimeContext.builder().userId("user").sessionId("session").build();
        var model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(100_000);
        var input = new ModelCallInput(List.of(), List.of(), GenerateOptions.builder().build(), model);
        var lifecycle = mock(RunLifecycleService.class);
        for (int maximum : List.of(0, 2, 500)) {
            var guard = new ExecutionGuardMiddleware(lifecycle, null, "session", maximum);
            int steps = maximum == 0 ? 60 : maximum;
            for (int step = 0; step < steps; step++) {
                guard.onModelCall(null, context, input, ignored -> Flux.empty()).blockLast(Duration.ofSeconds(2));
            }
            if (maximum > 0) {
                assertEquals("EXECUTION_STEP_LIMIT", assertThrows(ApiException.class,
                    () -> guard.onModelCall(null, context, input, ignored -> Flux.empty()).blockLast(Duration.ofSeconds(2))).code(),
                    "步数上限：" + maximum);
            }
        }
    }
}
