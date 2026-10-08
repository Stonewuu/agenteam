package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实模型请求验证无限制执行、超过十分钟的累计时间、有限超时和主动停止。
 */
@Import(SharedEnterpriseTestEdition.class)
class ExecutionTimeoutApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private RunMapper runs;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/execution-timeout/framework");
        registry.add("execution.state-root", () -> "target/execution-timeout/states");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1200, 3600})
    void savedLimitsAllowExecutionAfterElevenMinutesOfAccumulatedWork(int timeoutSeconds) throws Exception {
        configureAgent(config -> config.put("maxSteps", 0).put("timeoutSeconds", timeoutSeconds));
        allowModelCalls = true;
        modelResponse = exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                var response = Map.of("id", "long-execution", "choices", List.of(Map.of("index", 0,
                    "delta", Map.of("role", "assistant", "content", "累计执行超过十分钟后仍可完成。"), "finish_reason", "stop")));
                output.write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        };
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var lease = lifecycle.claim("execution-timeout-test").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        assertEquals(accepted.path("runId").asText(), run.id());
        assertEquals(timeoutSeconds, run.executionConfig().at("/config/timeoutSeconds").asInt());
        // 保存累计活动时间来模拟长任务，不让回归测试实际等待十一分钟。
        assertEquals(1, databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>()
            .eq(AgentRunRow::getEnterpriseId, enterprise).eq(AgentRunRow::getId, run.id())
            .set(AgentRunRow::getActiveMillis, Duration.ofMinutes(11).toMillis())));
        if (timeoutSeconds == 0) {
            assertEquals(Duration.ZERO, lifecycle.remaining(run));
        } else {
            assertTrue(lifecycle.remaining(run).compareTo(Duration.ofMinutes(8)) > 0);
        }
        try (var task = tasks.create(run, lease)) {
            task.completion().block(Duration.ofSeconds(10));
            task.saveCheckpoint();
            lifecycle.finish(lease, "completed", null, null);
        }
        assertEquals("completed", runs.find(enterprise, run.id(), false).orElseThrow().status());
        assertEquals(callsBefore + 1, modelCalls.get());
    }

    @Test
    void zeroTimeoutStillAllowsTheUserToStopAnUnfinishedModelRequest() throws Exception {
        configureAgent(config -> config.put("maxSteps", 0).put("timeoutSeconds", 0));
        var release = new CountDownLatch(1);
        waitForRelease(release);
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String id = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> modelCalls.get() > callsBefore);
            assertEquals("running", runs.find(enterprise, id, false).orElseThrow().status());
            write(base() + "/runs/" + id + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
            worker.poll();
            await(() -> runs.find(enterprise, id, false).orElseThrow().status().equals("cancelled"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void positiveTimeoutStillStopsAnUnfinishedModelRequest() throws Exception {
        configureAgent(config -> config.put("maxSteps", 0).put("timeoutSeconds", 1));
        var release = new CountDownLatch(1);
        waitForRelease(release);
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String id = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, id, false).orElseThrow().terminal());
            assertEquals("EXECUTION_TIMEOUT", runs.find(enterprise, id, false).orElseThrow().errorCode());
        } finally {
            release.countDown();
        }
    }

    private void waitForRelease(CountDownLatch release) {
        allowModelCalls = true;
        modelResponse = exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试模型未收到结束信号");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        };
    }
}
