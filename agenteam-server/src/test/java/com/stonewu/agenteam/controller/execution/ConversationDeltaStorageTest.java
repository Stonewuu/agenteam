package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentEventRow;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.service.execution.ConversationEventService;
import com.stonewu.agenteam.service.execution.ExecutionEventCompactionService;
import com.stonewu.agenteam.service.execution.ExecutionEventPublisher;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在独立 MySQL 与 Redis 中验证合并记录和实际对话续读，不访问开发库。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationDeltaStorageTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private ExecutionEventSqlMapper records;
    @Autowired
    private ExecutionEventCodec codec;
    @Autowired
    private ExecutionEventPublisher publisher;
    @Autowired
    private ExecutionEventCompactionService compaction;
    @Autowired
    private ConversationEventService connections;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private StringRedisTemplate redis;
    @MockitoSpyBean
    private ExecutionEventCache cache;
    @MockitoSpyBean
    private ExecutionEventStorageMapper storage;
    @MockitoSpyBean
    private ExecutionEventMapper eventRecords;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void savesHundredsOfDeltasInBoundedRowsAndResumesFromEveryOriginalPosition() throws Exception {
        var fixture = start();
        append(fixture, fixture.block(), 270, "中文🙂");
        var original = events.after(enterprise, fixture.conversation(), 0, 1000);
        assertEquals(275, original.size());
        var rows = deltaRows(fixture.conversation());
        assertEquals(2, rows.size());
        assertEquals(1, records.afterAgentEvent(enterprise, fixture.conversation(), 5, 100).size(),
            "读取一百个原片段时不应加载其后的合并记录");
        assertTrue(rows.stream().allMatch(row -> row.getStorageVersion() == 2));
        long before = original.stream().filter(event -> event.type().equals("message.delta"))
            .map(codec::encode).mapToLong(value -> value.data().getBytes(StandardCharsets.UTF_8).length).sum();
        long after = rows.stream().mapToLong(row -> row.getPayloadJson().getBytes(StandardCharsets.UTF_8).length).sum();
        assertTrue(after < before * 0.35);
        for (int position = 0; position <= original.size(); position++) {
            assertEquals(original.subList(position, Math.min(original.size(), position + 7)),
                events.after(enterprise, fixture.conversation(), position, 7));
        }
        var snapshot = snapshot(fixture);
        assertEquals("中文🙂".repeat(270), snapshot.at("/messages/1/content").asText());
        assertEquals("275", snapshot.path("lastSequence").asText());
        assertEquals("running", snapshot.at("/activeRun/status").asText());
    }

    @Test
    void partialPublishThenAppendKeepsOriginalHashesAndCannotMarkNewOutputPublished() throws Exception {
        var fixture = start();
        var block = append(fixture, fixture.block(), 130, "前");
        var original = events.after(enterprise, fixture.conversation(), 0, 1000);
        publisher.publish(enterprise, fixture.conversation());
        assertEquals(100, cache.last(enterprise, fixture.conversation()).sequence());
        assertNull(deltaRows(fixture.conversation()).getFirst().getPublishedAt());
        append(fixture, block, 20, "后");
        events.publishedThrough(enterprise, fixture.conversation(), 135, Instant.now());
        assertNull(deltaRows(fixture.conversation()).getFirst().getPublishedAt());
        publisher.publish(enterprise, fixture.conversation());
        publisher.publish(enterprise, fixture.conversation());
        var expected = events.after(enterprise, fixture.conversation(), 0, 1000);
        assertEquals(original, expected.subList(0, original.size()));
        assertEquals(expected, cache.after(enterprise, fixture.conversation(), 0, 1000));
        assertEquals(0, events.unpublished(enterprise, fixture.conversation(), 1000).size());
        redis.delete(cache.key(enterprise, fixture.conversation()));
        publisher.publish(enterprise, fixture.conversation());
        publisher.publish(enterprise, fixture.conversation());
        assertEquals(expected, cache.after(enterprise, fixture.conversation(), 0, 1000));
    }

    @Test
    void switchingBackUsesSnapshotThenReceivesNewOutputAndCompletionEvenWithoutRedis() throws Exception {
        var fixture = start();
        var block = append(fixture, fixture.block(), 80, "已保存🙂");
        var snapshot = snapshot(fixture);
        long position = snapshot.path("lastSequence").asLong();
        assertEquals("已保存🙂".repeat(80), snapshot.at("/messages/1/content").asText());
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        doThrow(new RedisConnectionFailureException("仅供验证的缓存故障")).when(cache)
            .after(anyString(), anyString(), anyLong(), anyInt());
        try {
            var liveDelta = new CountDownLatch(1);
            var receiving = connections.connect(actor, "测试连接", fixture.conversation(), Long.toString(position), null)
                .doOnNext(frame -> {
                    if ("message.delta".equals(frame.event())) {
                        liveDelta.countDown();
                    }
                })
                .takeUntil(frame -> "run.completed".equals(frame.event())).collectList().toFuture();
            append(fixture, block, 20, "新增🙂");
            assertTrue(liveDelta.await(5, TimeUnit.SECONDS), "执行尚未结束时就应收到新的流式正文");
            assertEquals("running", snapshot(fixture).at("/activeRun/status").asText());
            lifecycle.finish(fixture.lease(), "completed", null, null);
            var frames = receiving.get(10, TimeUnit.SECONDS);
            assertTrue(frames.stream().anyMatch(frame -> "stream.ready".equals(frame.event())));
            assertFalse(frames.stream().anyMatch(frame -> "stream.reset".equals(frame.event())));
            var received = frames.stream().filter(frame -> frame.id() != null)
                .map(frame -> (ExecutionEvent) frame.data()).toList();
            assertEquals(events.after(enterprise, fixture.conversation(), position, 1000), received);
            String text = received.stream().filter(event -> event.type().equals("message.delta"))
                .map(event -> (String) event.payload().get("delta")).reduce("", String::concat);
            assertEquals("新增🙂".repeat(20), text);
            var complete = snapshot(fixture);
            assertEquals("已保存🙂".repeat(80) + text, complete.at("/messages/1/content").asText());
            assertTrue(complete.path("activeRun").isNull());
        } finally {
            doCallRealMethod().when(cache).after(anyString(), anyString(), anyLong(), anyInt());
        }
    }

    @Test
    void batchRetryAndTransactionFailureCannotDuplicateOrPartiallyAppendText() throws Exception {
        var fixture = start();
        var block = append(fixture, fixture.block(), 20, "前");
        var before = events.after(enterprise, fixture.conversation(), 0, 1000);
        var next = block.update(block.text() + "后", "running");
        String batch = UUID.randomUUID().toString();
        var valid = ExecutionChange.delta(next, block.revision(), "后");
        assertThrows(IllegalStateException.class, () -> messageWriter.save(fixture.lease(), batch, List.of(valid, valid)));
        assertEquals(before, events.after(enterprise, fixture.conversation(), 0, 1000));
        assertEquals(block.text(), snapshot(fixture).at("/messages/1/content").asText());
        messageWriter.save(fixture.lease(), batch, List.of(valid));
        messageWriter.save(fixture.lease(), batch, List.of(valid));
        assertEquals(before.size() + 1, events.after(enterprise, fixture.conversation(), 0, 1000).size());
        assertEquals(next.text(), snapshot(fixture).at("/messages/1/content").asText());
        assertEquals(1, deltaRows(fixture.conversation()).size());
    }

    @Test
    void legacyConversionPreservesEveryEventAndRollbackRestoresDeletedRows() throws Exception {
        var fixture = start();
        append(fixture, fixture.block(), 90, "历史🙂");
        var expected = events.after(enterprise, fixture.conversation(), 0, 1000);
        var expectedSnapshot = snapshot(fixture);
        expandLegacy(fixture, expected);
        publisher.publish(enterprise, fixture.conversation());
        assertEquals(90, deltaRows(fixture.conversation()).size());
        doThrow(new DataAccessResourceFailureException("仅供验证的合并保存失败")).when(storage)
            .replace(any(AgentEventRow.class), anyList(), anyLong());
        try {
            assertThrows(DataAccessResourceFailureException.class, () -> compaction.compact(enterprise,
                fixture.conversation(), fixture.lease().runId(), 0));
            assertEquals(90, deltaRows(fixture.conversation()).size());
            assertEquals(expected, events.after(enterprise, fixture.conversation(), 0, 1000));
        } finally {
            doCallRealMethod().when(storage).replace(any(AgentEventRow.class), anyList(), anyLong());
        }
        var result = compaction.compact(enterprise, fixture.conversation(), fixture.lease().runId(), 0);
        assertEquals(90, result.convertedRows());
        assertEquals(89, result.removedRows());
        assertEquals(expected, events.after(enterprise, fixture.conversation(), 0, 1000));
        assertEquals(expectedSnapshot, snapshot(fixture));
        assertEquals(0, compaction.compact(enterprise, fixture.conversation(), fixture.lease().runId(), 0).convertedRows());
        publisher.publish(enterprise, fixture.conversation());
        assertEquals(expected, cache.after(enterprise, fixture.conversation(), 0, 1000));
        assertEquals(expected.subList(52, 59), events.after(enterprise, fixture.conversation(), 52, 7));
    }

    @Test
    void blockReplacementsAndInterleavedThinkingAreNeverMergedIntoAnotherBlock() throws Exception {
        var fixture = start();
        var first = append(fixture, fixture.block(), 3, "正文");
        var thinking = new ContentBlock("thinking", "thinking", null, 2, "1", "", "running", null, null, null, null, null, null);
        messageWriter.save(fixture.lease(), UUID.randomUUID().toString(), List.of(ExecutionChange.replace(thinking)));
        append(fixture, first, 2, "继续");
        append(fixture, thinking, 2, "思考");
        assertEquals(3, deltaRows(fixture.conversation()).size());
        var snapshot = snapshot(fixture);
        assertEquals("正文".repeat(3) + "继续".repeat(2), snapshot.at("/messages/1/content").asText());
        assertEquals("思考".repeat(2), snapshot.at("/messages/1/blocks/1/text").asText());
    }

    @Test
    void historicalConversionAndNewOutputUseTheSameConversationLock() throws Exception {
        var fixture = start();
        var block = append(fixture, fixture.block(), 80, "原");
        expandLegacy(fixture, events.after(enterprise, fixture.conversation(), 0, 1000));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(storage).replace(any(AgentEventRow.class), anyList(), anyLong());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var converting = executor.submit(() -> compaction.compact(enterprise, fixture.conversation(), fixture.lease().runId(), 0));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var writing = executor.submit(() -> append(fixture, block, 10, "新"));
            try {
                assertThrows(TimeoutException.class, () -> writing.get(150, TimeUnit.MILLISECONDS));
                assertEquals(block.text(), snapshot(fixture).at("/messages/1/content").asText());
            } finally {
                release.countDown();
            }
            assertEquals(79, converting.get(10, TimeUnit.SECONDS).removedRows());
            writing.get(10, TimeUnit.SECONDS);
            assertEquals("原".repeat(80) + "新".repeat(10), snapshot(fixture).at("/messages/1/content").asText());
            assertEquals(95, events.after(enterprise, fixture.conversation(), 0, 1000).size());
            assertEquals(1, deltaRows(fixture.conversation()).size());
        } finally {
            release.countDown();
            doCallRealMethod().when(storage).replace(any(AgentEventRow.class), anyList(), anyLong());
        }
    }

    @Test
    void redisAcknowledgementBeforeDatabaseFailureCanRetryMergedEventsWithoutDuplicates() throws Exception {
        var fixture = start();
        append(fixture, fixture.block(), 60, "已确认");
        doThrow(new DataAccessResourceFailureException("仅供验证的分发确认失败")).when(eventRecords)
            .publishedThrough(anyString(), anyString(), anyLong(), any(Instant.class));
        try {
            assertThrows(DataAccessResourceFailureException.class, () -> publisher.publish(enterprise, fixture.conversation()));
        } finally {
            doCallRealMethod().when(eventRecords).publishedThrough(anyString(), anyString(), anyLong(), any(Instant.class));
        }
        assertNull(deltaRows(fixture.conversation()).getFirst().getPublishedAt());
        publisher.publish(enterprise, fixture.conversation());
        assertEquals(events.after(enterprise, fixture.conversation(), 0, 1000), cache.after(enterprise, fixture.conversation(), 0, 1000));
        assertEquals(0, events.unpublished(enterprise, fixture.conversation(), 1000).size());
    }

    private record Fixture(String conversation, JobLease lease, ContentBlock block) {
    }

    private Fixture start() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        var lease = lifecycle.claim("增量合并测试").orElseThrow();
        lifecycle.start(lease).orElseThrow();
        var block = new ContentBlock("text", "text", null, 1, "1", "", "running", null, null, null, null, null, null);
        messageWriter.save(lease, UUID.randomUUID().toString(), List.of(ExecutionChange.replace(block)));
        return new Fixture(accepted.path("conversationId").asText(), lease, block);
    }

    private ContentBlock append(Fixture fixture, ContentBlock previous, int count, String text) {
        var changes = new ArrayList<ExecutionChange>();
        var block = previous;
        for (int i = 0; i < count; i++) {
            var next = block.update(block.text() + text, "running");
            changes.add(ExecutionChange.delta(next, block.revision(), text));
            block = next;
        }
        messageWriter.save(fixture.lease(), UUID.randomUUID().toString(), changes);
        return block;
    }

    private List<AgentEventRow> deltaRows(String conversation) {
        return records.selectList(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getEnterpriseId, enterprise)
            .eq(AgentEventRow::getConversationId, conversation).eq(AgentEventRow::getEventType, "message.delta")
            .orderByAsc(AgentEventRow::getConversationSequence));
    }

    private JsonNode snapshot(Fixture fixture) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/" + fixture.conversation()).cookie(cookie))
            .andExpect(status().isOk()).andReturn());
    }

    private void expandLegacy(Fixture fixture, List<ExecutionEvent> values) {
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            records.delete(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getEnterpriseId, enterprise)
                .eq(AgentEventRow::getConversationId, fixture.conversation()).eq(AgentEventRow::getEventType, "message.delta"));
            for (var event : values) {
                if (!event.type().equals("message.delta")) {
                    continue;
                }
                var encoded = codec.encode(event);
                var row = new AgentEventRow();
                row.setEventId(event.eventId());
                row.setEnterpriseId(enterprise);
                row.setConversationId(fixture.conversation());
                row.setRunId(fixture.lease().runId());
                row.setEventType(event.type());
                row.setConversationSequence(Long.parseLong(event.sequence()));
                row.setSequenceNo(Long.parseLong(event.sequence()));
                row.setPayloadJson(encoded.data());
                row.setPayloadHash(encoded.hash());
                row.setStorageVersion(1);
                row.setCreatedAt(Instant.parse(event.createdAt()));
                row.setStartedAt(Instant.parse(event.createdAt()));
                row.setFinishedAt(Instant.parse(event.createdAt()));
                records.insert(row);
            }
        });
    }
}
