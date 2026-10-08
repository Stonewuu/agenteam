package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.AgentEventRow;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ConversationSnapshotView;
import com.stonewu.agenteam.service.execution.*;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在独立 MySQL 和 Redis 中验证输出期间不保存正文，以及刷新后接续输出。
 */
@Import(SharedEnterpriseTestEdition.class)
class LiveConversationExecutionTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private ExecutionPresentationFactory presentation;
    @Autowired
    private ExecutionMessageMapper messages;
    @Autowired
    private ExecutionEventSqlMapper records;
    @Autowired
    private ConversationSnapshotService snapshots;
    @Autowired
    private ConversationEventService connections;
    @Autowired
    private LiveEventDelivery delivery;
    @Autowired
    private RunMapper runs;
    @MockitoSpyBean
    private ConversationQueryService queries;
    @MockitoSpyBean
    private ExecutionMessageWriter writer;
    @MockitoSpyBean
    private ConversationMapper conversations;
    @MockitoSpyBean
    private LiveEventCache live;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.events.live-enabled", () -> "true");
        registry.add("execution.events.live-retained-events", () -> "4");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void oneThousandFragmentsStayOutOfMysqlUntilTheBlockEndsAndRefreshResumes() throws Exception {
        var fixture = start();
        try (var frames = presentation.open(fixture.run(), fixture.lease())) {
            clearInvocations(writer, conversations);
            frames.accept(event("TEXT_BLOCK_START", "reply", "text", null));
            for (int i = 0; i < 1000; i++) {
                frames.accept(event("TEXT_BLOCK_DELTA", "reply", "text", "字"));
            }
            frames.flush();
            assertEquals("", saved(fixture));
            assertEquals(0, textEvents(fixture));
            verify(writer, never()).save(any(), anyString(), anyList());
            verify(conversations, never()).advanceSequence(anyString(), anyString(), any());
            var before = snapshot(fixture);
            assertEquals("字".repeat(1000), displayed(before, fixture));
            assertNotNull(before.streamCursor());

            frames.accept(event("TEXT_BLOCK_DELTA", "reply", "text", "尾"));
            frames.flush();
            var resumed = live.after(enterprise, fixture.run().conversationId(), before.streamCursor(), Duration.ZERO, 100);
            assertEquals(1, resumed.size());
            assertEquals("尾", resumed.getFirst().payload().get("delta"));
            assertEquals("", saved(fixture));

            frames.accept(event("TEXT_BLOCK_END", "reply", "text", null));
            frames.flush();
            assertEquals("字".repeat(1000) + "尾", saved(fixture));
            assertEquals(1, textEvents(fixture));
            verify(writer, times(1)).save(any(), anyString(), anyList());
            verify(conversations, times(1)).advanceSequence(anyString(), anyString(), any());
            assertEquals(saved(fixture), displayed(snapshot(fixture), fixture));
        } finally {
            lifecycle.finish(fixture.lease(), "completed", null, null);
        }
    }

    @Test
    void endingOneParallelBlockDoesNotSaveTheOtherRunningBlock() throws Exception {
        var fixture = start();
        try (var frames = presentation.open(fixture.run(), fixture.lease())) {
            var first = frames.stream();
            var second = frames.stream();
            first.accept(event("TEXT_BLOCK_START", "first", "first", null));
            first.accept(event("TEXT_BLOCK_DELTA", "first", "first", "第一段"));
            second.accept(event("TEXT_BLOCK_START", "second", "second", null));
            second.accept(event("TEXT_BLOCK_DELTA", "second", "second", "第二段还在输出"));
            frames.flush();
            first.finish("completed");
            assertEquals("第一段", saved(fixture));
            assertEquals("第一段\n\n第二段还在输出", displayed(snapshot(fixture), fixture));
            assertEquals(1, textEvents(fixture));
            second.finish("cancelled");
            assertEquals("第一段\n\n第二段还在输出", saved(fixture));
            assertEquals(2, textEvents(fixture));
        } finally {
            lifecycle.finish(fixture.lease(), "cancelled", null, null);
        }
    }

    @Test
    void trimmingOldRedisEventsKeepsTheEntireUnfinishedBlock() throws Exception {
        var fixture = start();
        try (var frames = presentation.open(fixture.run(), fixture.lease())) {
            frames.accept(event("TEXT_BLOCK_START", "reply", "text", null));
            frames.accept(event("TEXT_BLOCK_DELTA", "reply", "text", "开头"));
            frames.flush();
            var initial = snapshot(fixture);
            for (int i = 0; i < 8; i++) {
                frames.accept(event("TEXT_BLOCK_DELTA", "reply", "text", Integer.toString(i)));
                frames.flush();
            }
            var reset = connections.connect(actor(), "read-only-test", fixture.run().conversationId(), initial.streamCursor().sequence(),
                    null, initial.streamCursor().generation()).filter(frame -> "stream.reset".equals(frame.event()))
                .next().block(Duration.ofSeconds(5));
            assertNotNull(reset);
            assertEquals("开头01234567", displayed(snapshot(fixture), fixture));
            assertEquals("", saved(fixture));
            frames.accept(event("TEXT_BLOCK_END", "reply", "text", null));
            frames.flush();
        } finally {
            lifecycle.finish(fixture.lease(), "completed", null, null);
        }
    }

    @Test
    void oneHundredWaitingReadersDoNotPollMysqlForStreamPositions() throws Exception {
        var fixture = start();
        try (var frames = presentation.open(fixture.run(), fixture.lease())) {
            frames.accept(event("TEXT_BLOCK_START", "reply", "text", null));
            frames.accept(event("TEXT_BLOCK_DELTA", "reply", "text", "等待新的输出"));
            frames.flush();
            var snapshot = snapshot(fixture);
            var actor = actor();
            clearInvocations(conversations);
            var received = Flux.range(0, 100).flatMap(index -> connections.connect(actor, "read-only-test", fixture.run().conversationId(),
                    snapshot.streamCursor().sequence(), null, snapshot.streamCursor().generation())
                .filter(frame -> "stream.ready".equals(frame.event())).take(1), 100).collectList().block(Duration.ofSeconds(12));
            assertNotNull(received);
            assertEquals(100, received.size());
            verify(conversations, never()).streamPosition(anyString(), anyString(), anyString(), any());
            assertEquals("", saved(fixture));
            frames.accept(event("TEXT_BLOCK_END", "reply", "text", null));
            frames.flush();
        } finally {
            lifecycle.finish(fixture.lease(), "completed", null, null);
        }
    }

    @Test
    void redisWriteFailureDoesNotPreventSavingTheFinishedBlock() throws Exception {
        var fixture = start();
        try (var frames = presentation.open(fixture.run(), fixture.lease())) {
            doThrow(new RedisConnectionFailureException("仅模拟本次实时输出写入失败"))
                .when(live).append(anyString(), anyString(), any(), any(), any());
            frames.accept(event("TEXT_BLOCK_START", "reply", "text", null));
            frames.accept(event("TEXT_BLOCK_DELTA", "reply", "text", "内存中的完整回复"));
            frames.accept(event("TEXT_BLOCK_END", "reply", "text", null));
            frames.flush();
            assertEquals("内存中的完整回复", saved(fixture));
            assertEquals(1, textEvents(fixture));
        } finally {
            doCallRealMethod().when(live).append(anyString(), anyString(), any(), any(), any());
            lifecycle.finish(fixture.lease(), "completed", null, null);
        }
        assertEquals("内存中的完整回复", displayed(snapshot(fixture), fixture));
    }

    private record Fixture(RunRecord run, JobLease lease) {
    }

    @Test
    void snapshotRetriesWhenTheBlockIsSavedAndRedisIsCleanedBetweenItsTwoReads() throws Exception {
        var fixture = start();
        try (var frames = presentation.open(fixture.run(), fixture.lease())) {
            frames.accept(event("TEXT_BLOCK_START", "reply", "text", null));
            frames.accept(event("TEXT_BLOCK_DELTA", "reply", "text", "保存交接中的完整内容"));
            frames.flush();
            var once = new AtomicBoolean();
            doAnswer(call -> {
                if (once.compareAndSet(false, true)) {
                    frames.accept(event("TEXT_BLOCK_END", "reply", "text", null));
                    frames.flush();
                    delivery.recover(fixture.run());
                    assertTrue(live.compact(enterprise, fixture.run().conversationId()));
                }
                return call.callRealMethod();
            }).when(live).snapshot(enterprise, fixture.run().conversationId());
            clearInvocations(queries);
            assertEquals("保存交接中的完整内容", displayed(snapshot(fixture), fixture));
            verify(queries, atLeast(2)).snapshotBase(any(), anyString());
        } finally {
            doCallRealMethod().when(live).snapshot(anyString(), anyString());
            lifecycle.finish(fixture.lease(), "completed", null, null);
        }
    }

    @Test
    void expiredWorkerRecoversAlreadyDisplayedTextFromRedisBeforeEndingTheRun() throws Exception {
        var fixture = start();
        var block = new ContentBlock("recovered-text", "text", null, 1, "23", "进程中断前已输出的内容", "running",
            null, null, null, null, null, null);
        try (var session = delivery.open(fixture.run(), fixture.lease(), List.of())) {
            session.publish(List.of(ExecutionChange.replace(block)), List.of(block));
            session.drain();
        }
        assertEquals("", saved(fixture));
        databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>()
            .eq(BackgroundJobRow::getId, fixture.lease().id()).set(BackgroundJobRow::getLeaseUntil, Instant.now().minusSeconds(1)));
        lifecycle.recover(fixture.lease());
        assertEquals("failed", runs.find(enterprise, fixture.run().id(), false).orElseThrow().status());
        assertEquals("进程中断前已输出的内容", saved(fixture));
        var output = messages.find(enterprise, fixture.run().conversationId(), fixture.run().outputMessageId()).orElseThrow();
        assertEquals("failed", output.blocks().getFirst().status());
        assertFalse(live.renew(enterprise, fixture.run().conversationId(), snapshot(fixture).streamCursor(),
            fixture.lease(), Instant.now().plusSeconds(30), Instant.now()));
    }

    @Test
    void realModelThinkingStreamsBeforeDatabaseSaveAndFinalOutputRemainsComplete() throws Exception {
        var finishModel = new CountDownLatch(1);
        modelResponse = exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                modelFrame(exchange, Map.of("reasoning_content", "正在核对事实。"), null);
                if (!finishModel.await(12, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("没有收到结束模型测试的信号");
                }
                modelFrame(exchange, Map.of("reasoning_content", "已经完成核对。"), null);
                modelFrame(exchange, Map.of("content", "这是完整答复。"), "stop");
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("模型测试被中断", interrupted);
            } finally {
                exchange.close();
            }
        };
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("分析这段内容")),
            UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String runId = accepted.path("runId").asText();
        String conversation = accepted.path("conversationId").asText();
        String outputId = accepted.path("outputMessageId").asText();
        allowModelCalls = true;
        var actor = actor();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> snapshots.read(actor, conversation).messages().stream().flatMap(message -> message.blocks().stream())
                .anyMatch(block -> block.type().equals("thinking") && block.text().contains("正在核对事实。")));
            assertTrue(messages.find(enterprise, conversation, outputId).orElseThrow().blocks().stream()
                .noneMatch(block -> block.type().equals("thinking")));
            var streaming = snapshots.read(actor, conversation);
            schemas.validate("ConversationSnapshot", json.valueToTree(streaming));
            assertNotNull(streaming.streamCursor());
            finishModel.countDown();
            await(() -> runs.find(enterprise, runId, false).orElseThrow().terminal());
            assertEquals("completed", runs.find(enterprise, runId, false).orElseThrow().status());
            var saved = messages.find(enterprise, conversation, outputId).orElseThrow();
            assertEquals("这是完整答复。", saved.content());
            assertEquals("正在核对事实。已经完成核对。", saved.blocks().stream().filter(block -> block.type().equals("thinking"))
                .findFirst().orElseThrow().text());
            assertEquals(0, records.selectCount(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getRunId, runId)
                .eq(AgentEventRow::getEventType, "message.delta")));
        } finally {
            finishModel.countDown();
        }
    }

    private void modelFrame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        var choice = json.createObjectNode().put("index", 0);
        choice.set("delta", json.valueToTree(delta));
        if (finish != null) {
            choice.put("finish_reason", finish);
        }
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(Map.of("id", "live-model-response", "choices", List.of(choice)))
            + "\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    @Test
    void slowDatabaseSaveDoesNotBlockTheNextContentBlocksLiveOutput() throws Exception {
        var fixture = start();
        var saving = new CountDownLatch(1);
        var continueSave = new CountDownLatch(1);
        var first = new AtomicBoolean();
        doAnswer(call -> {
            if (first.compareAndSet(false, true)) {
                saving.countDown();
                if (!continueSave.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("没有收到继续保存的测试信号");
                }
            }
            return call.callRealMethod();
        }).when(writer).save(any(), anyString(), anyList());
        try (var frames = presentation.open(fixture.run(), fixture.lease())) {
            var firstStream = frames.stream();
            var secondStream = frames.stream();
            CompletableFuture<Void> finished = null;
            try {
                firstStream.accept(event("TEXT_BLOCK_START", "first", "first", null));
                firstStream.accept(event("TEXT_BLOCK_DELTA", "first", "first", "第一段已完成"));
                finished = CompletableFuture.runAsync(() -> firstStream.finish("completed"));
                assertTrue(saving.await(5, TimeUnit.SECONDS));
                secondStream.accept(event("TEXT_BLOCK_START", "second", "second", null));
                secondStream.accept(event("TEXT_BLOCK_DELTA", "second", "second", "第二段继续输出"));
                await(() -> live.snapshot(enterprise, fixture.run().conversationId()).orElseThrow().objects().values().stream()
                    .anyMatch(value -> value.path("block").path("text").asText().contains("第二段继续输出")));
                assertEquals("第一段已完成\n\n第二段继续输出", displayed(snapshot(fixture), fixture));
                assertEquals("", saved(fixture));
            } finally {
                continueSave.countDown();
            }
            if (finished != null) {
                finished.get(5, TimeUnit.SECONDS);
            }
            secondStream.finish("completed");
            frames.flush();
            assertEquals("第一段已完成\n\n第二段继续输出", saved(fixture));
        } finally {
            continueSave.countDown();
            doCallRealMethod().when(writer).save(any(), anyString(), anyList());
            lifecycle.finish(fixture.lease(), "completed", null, null);
        }
    }

    private Fixture start() throws Exception {
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("验证实时输出")),
            UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var lease = lifecycle.claim("live-test-" + UUID.randomUUID()).orElseThrow();
        assertEquals(accepted.path("runId").asText(), lease.runId());
        var run = lifecycle.start(lease).orElseThrow();
        assertTrue(conversations.find(enterprise, admin, run.conversationId(), false).orElseThrow().liveEvents());
        return new Fixture(run, lease);
    }

    private AgentStreamEvent event(String type, String reply, String block, String delta) {
        return new AgentStreamEvent(UUID.randomUUID().toString(), type, null, reply, block, delta, null, null,
            null, null, null, Instant.now().toString(), Map.of(), Map.of());
    }

    private AuthContext actor() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }

    private ConversationSnapshotView snapshot(Fixture fixture) {
        var snapshot = snapshots.read(actor(), fixture.run().conversationId());
        assertEquals(2, snapshot.protocolVersion());
        assertNotNull(snapshot.streamCursor());
        return snapshot;
    }

    private String displayed(ConversationSnapshotView snapshot, Fixture fixture) {
        return snapshot.messages().stream().filter(message -> message.id().equals(fixture.run().outputMessageId())).findFirst().orElseThrow().content();
    }

    private String saved(Fixture fixture) {
        return messages.find(enterprise, fixture.run().conversationId(), fixture.run().outputMessageId()).orElseThrow().content();
    }

    private long textEvents(Fixture fixture) {
        assertEquals(0, records.selectCount(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getRunId, fixture.run().id())
            .eq(AgentEventRow::getEventType, "message.delta")));
        return records.selectCount(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getRunId, fixture.run().id())
            .eq(AgentEventRow::getEventType, "block.updated"));
    }
}
