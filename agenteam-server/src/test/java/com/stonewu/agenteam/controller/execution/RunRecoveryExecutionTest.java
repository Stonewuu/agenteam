package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunCheckpointRow;
import com.stonewu.agenteam.service.agent.ExecutionPaths;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实模型连接中的损坏、空回复与取消，不得丢失已保存内容或偷偷新建上下文。
 */
@Import(SharedEnterpriseTestEdition.class)
class RunRecoveryExecutionTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private ExecutionPaths paths;

    @Autowired
    private RunMapper runs;

    @Autowired
    private RunCheckpointMapper checkpoints;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/p03-recovery-workspace");
        registry.add("execution.state-root", () -> "target/p03-recovery-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void corruptedFrameworkStateStopsBeforeAnotherModelCallWhileMessagesRemainReadable() throws Exception {
        modelResponse = exchange -> respond(exchange, "第一次的完整结果");
        var first = submit("第一次");
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> state(first).equals("completed"));
            assertEquals(1, count("run_checkpoint"));
            var manifest = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunCheckpointSqlMapper.class).selectList(new LambdaQueryWrapper<RunCheckpointRow>().select(RunCheckpointRow::getStateJson).eq(RunCheckpointRow::getRunId, (first.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getStateJson()).toList()));
            String relative = manifest.path("files").fieldNames().next();
            var checkpoint = checkpoints.find(enterprise, first.path("runId").asText()).orElseThrow();
            var run = runs.find(enterprise, first.path("runId").asText(), false).orElseThrow();
            var directory = paths.checkpointPath(run, checkpoint.leaseVersion(), checkpoint.stateKey());
            var file = directory.resolve(relative).normalize();
            assertTrue(file.startsWith(directory));
            Files.writeString(file, "此内容仅用于验证损坏状态，不能当作空对话继续");
            var next = send(first, "继续处理");
            int calls = modelCalls.get();
            worker.poll();
            await(() -> state(next).equals("failed"));
            assertEquals("EXECUTION_STATE_UNAVAILABLE", code(next));
            assertEquals(calls, modelCalls.get());
            assertEquals(0, reserved());
            var snapshot = snapshot(first);
            schemas.validate("ConversationSnapshot", snapshot);
            assertEquals("第一次的完整结果", snapshot.at("/messages/1/content").asText());
        } finally {
            worker.close();
        }
    }

    @Test
    void emptyModelReplyDoesNotProduceCompletedRun() throws Exception {
        modelResponse = exchange -> respond(exchange, "");
        var accepted = submit("请返回内容");
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> !Set.of("queued", "running").contains(state(accepted)));
            assertEquals("failed", state(accepted));
            assertEquals("EXECUTION_EMPTY_RESULT", code(accepted));
            var snapshot = snapshot(accepted);
            schemas.validate("ConversationSnapshot", snapshot);
            assertEquals("failed", snapshot.at("/messages/1/status").asText());
            assertEquals("", snapshot.at("/messages/1/content").asText());
            assertEquals(0, reserved());
        } finally {
            worker.close();
        }
    }

    @Test
    void cancelledPartialReplyIsIncludedAfterAnEarlierSuccessfulRunAndLateTextCannotReplaceIt() throws Exception {
        modelResponse = exchange -> respond(exchange, "已经完成的旧回答");
        var first = submit("最初的问题");
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        var finish = new CountDownLatch(1);
        var received = new AtomicBoolean();
        try {
            worker.poll();
            await(() -> state(first).equals("completed"));
            modelResponse = exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    begin(exchange);
                    frame(exchange, "需要保留的部分回复", false);
                    if (finish.await(20, TimeUnit.SECONDS)) {
                        frame(exchange, "晚到的内容", true);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException disconnected) {
                    if (finish.getCount() != 0) {
                        throw disconnected;
                    }
                } finally {
                    exchange.close();
                }
            };
            var stopped = send(first, "中途停止的问题");
            worker.poll();
            await(() -> "需要保留的部分回复".equals(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getContent).eq(AgentMessageRow::getId, (stopped.path("outputMessageId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getContent()).toList())));
            write(base() + "/runs/" + stopped.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
            var pollingThread = new AtomicReference<Thread>();
            try (var polling = Executors.newVirtualThreadPerTaskExecutor()) {
                var stoppedPromptly = new CountDownLatch(1);
                polling.submit(() -> {
                    pollingThread.set(Thread.currentThread());
                    worker.poll();
                    stoppedPromptly.countDown();
                });
                try {
                    assertTrue(stoppedPromptly.await(2, TimeUnit.SECONDS), () -> "发送停止信号阻塞了工作检查：" + Arrays.toString(pollingThread.get().getStackTrace()));
                } catch (AssertionError failed) {
                    finish.countDown();
                    throw failed;
                }
            }
            await(() -> state(stopped).equals("cancelled"));
            finish.countDown();
            modelResponse = exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.set(body.contains("中途停止的问题") && body.contains("需要保留的部分回复") && body.contains("已经完成的旧回答"));
                begin(exchange);
                frame(exchange, "根据已保存内容继续回答", true);
                exchange.close();
            };
            var next = send(first, "继续处理");
            worker.poll();
            await(() -> !Set.of("queued", "running").contains(state(next)));
            assertEquals("completed", state(next));
            assertTrue(received.get(), "最近已保存的中断正文必须进入新一轮上下文");
            var snapshot = snapshot(first);
            assertEquals(6, snapshot.path("messages").size());
            assertEquals("需要保留的部分回复", snapshot.at("/messages/3/content").asText());
            assertEquals("cancelled", snapshot.at("/messages/3/status").asText());
        } finally {
            finish.countDown();
            worker.close();
        }
    }

    private JsonNode submit(String text) throws Exception {
        return data(write(base() + "/conversations", Map.of("agentId", agent, "input", input(text)), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private JsonNode send(JsonNode accepted, String text) throws Exception {
        return data(write(base() + "/conversations/" + accepted.path("conversationId").asText() + "/messages", input(text), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private String state(JsonNode accepted) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    private String code(JsonNode accepted) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList());
    }

    private void respond(HttpExchange exchange, String text) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            begin(exchange);
            frame(exchange, text, true);
        } finally {
            exchange.close();
        }
    }

    private void begin(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    private void frame(HttpExchange exchange, String text, boolean finished) throws IOException {
        var choice = json.createObjectNode().put("index", 0);
        choice.putObject("delta").put("content", text);
        if (finished) {
            choice.put("finish_reason", "stop");
        }
        String frame = "data: " + json.writeValueAsString(Map.of("id", "controlled-recovery-response", "choices", List.of(choice))) + "\n\n" + (finished ? "data: [DONE]\n\n" : "");
        exchange.getResponseBody().write(frame.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }
}
