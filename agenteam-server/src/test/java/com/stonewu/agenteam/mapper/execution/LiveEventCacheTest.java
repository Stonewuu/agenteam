package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.model.execution.response.StreamCursor;
import com.stonewu.agenteam.support.IsolatedInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用实际 Redis 验证脚本、累计文本、编号、保存交接及失效的旧写入资格。
 */
class LiveEventCacheTest {
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final LiveEventCodec codec = new LiveEventCodec(json);
    private LiveEventCache cache;
    private RunRecord run;
    private JobLease lease;
    private StreamCursor cursor;
    private Instant now;

    @BeforeAll
    static void connect() {
        var server = IsolatedInfrastructure.redis();
        factory = new LettuceConnectionFactory(server.getHost(), server.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void initialize() {
        now = Instant.now();
        String conversation = UUID.randomUUID().toString();
        run = new RunRecord("run", "cache-test", conversation, "user", "input", "output", "version", "interactive", "running",
            json.createObjectNode(), 1, 1, 1, false, 0, now, null, null, null, null, null, now);
        lease = new JobLease("job", run.enterpriseId(), "user", run.id(), "worker", 1, now.plusSeconds(30));
        cache = new LiveEventCache(redis, codec, 3);
        cursor = initialize(null, 0);
        assertTrue(cache.renew(run.enterpriseId(), run.conversationId(), cursor, lease, lease.until(), now));
    }

    @Test
    void appendsOnlyNewTextAndDuplicateRequestsDoNotDuplicateIt() {
        var block = block("text", "", "1", "running");
        publish(ExecutionChange.replace(block));
        var next = block.update("中文🙂", "running");
        var mutation = codec.change(run, ExecutionChange.delta(next, "1", "中文🙂"), now);
        var result = cache.append(run.enterpriseId(), run.conversationId(), cursor, LiveEventCache.token(lease), mutation);
        assertTrue(result.accepted());
        var duplicate = cache.append(run.enterpriseId(), run.conversationId(), cursor, LiveEventCache.token(lease), mutation);
        assertEquals("duplicate", duplicate.status());
        assertEquals(result.cursor(), duplicate.cursor());
        var snapshot = cache.snapshot(run.enterpriseId(), run.conversationId()).orElseThrow();
        assertEquals("中文🙂", snapshot.objects().get("block:output:text").path("block").path("text").asText());
        assertEquals("2", snapshot.cursor().sequence());
    }

    @Test
    void trimmingEventsDoesNotTrimAccumulatedTextOrChangeItsPosition() {
        var block = block("text", "", "1", "running");
        publish(ExecutionChange.replace(block));
        for (int i = 0; i < 12; i++) {
            var next = block.update(block.text() + "字", "running");
            publish(ExecutionChange.delta(next, block.revision(), "字"));
            block = next;
        }
        var snapshot = cache.snapshot(run.enterpriseId(), run.conversationId()).orElseThrow();
        assertEquals("字".repeat(12), snapshot.objects().get("block:output:text").path("block").path("text").asText());
        assertEquals("13", snapshot.cursor().sequence());
        var tail = cache.after(run.enterpriseId(), run.conversationId(), new StreamCursor(cursor.generation(), "0"), Duration.ZERO, 100);
        assertEquals(3, tail.size());
        assertEquals("11", tail.getFirst().sequence());
        assertFalse(cache.compact(run.enterpriseId(), run.conversationId()));
    }

    @Test
    void commitAcknowledgementDoesNotReplayTextAndCleanupRequiresANewerDatabaseSnapshot() {
        var finished = block("text", "完整回复", "51", "completed");
        publish(ExecutionChange.replace(finished));
        var before = cache.snapshot(run.enterpriseId(), run.conversationId()).orElseThrow();
        assertTrue(before.covers(0));
        var result = cache.append(run.enterpriseId(), run.conversationId(), cursor, null, codec.committed(saved(finished, 1)));
        assertTrue(result.accepted());
        assertEquals(cursor.sequence(), result.cursor().sequence());
        assertEquals(1, result.databaseVersion());
        assertTrue(cache.compact(run.enterpriseId(), run.conversationId()));
        var after = cache.snapshot(run.enterpriseId(), run.conversationId()).orElseThrow();
        assertFalse(after.covers(0));
        assertTrue(after.covers(1));
        assertTrue(after.objects().isEmpty());
        assertEquals("完整回复", before.objects().get("block:output:text").path("block").path("text").asText());
    }

    @Test
    void rejectsOutOfOrderDatabaseNotificationsWithoutChangingContent() {
        var first = block("text", "前", "1", "completed");
        var next = first.update("前后", "completed");
        var early = cache.append(run.enterpriseId(), run.conversationId(), cursor, null, codec.committed(saved(next, 2)));
        assertEquals("database_order", early.status());
        assertTrue(cache.snapshot(run.enterpriseId(), run.conversationId()).orElseThrow().objects().isEmpty());
        assertTrue(cache.append(run.enterpriseId(), run.conversationId(), cursor, null, codec.committed(saved(first, 1))).accepted());
        assertTrue(cache.append(run.enterpriseId(), run.conversationId(), cursor, null, codec.committed(saved(next, 2))).accepted());
        assertEquals("前后", cache.snapshot(run.enterpriseId(), run.conversationId()).orElseThrow()
            .objects().get("block:output:text").path("block").path("text").asText());
    }

    @Test
    void exactLargeNumbersSurviveLuaAndEventJsonEncoding() {
        redis.opsForValue().set(key() + ":sequence", "9007199254740992");
        cursor = cache.position(run.enterpriseId(), run.conversationId()).orElseThrow().cursor();
        publish(ExecutionChange.replace(block("text", "正文", "1", "running")));
        assertEquals("9007199254740993", cursor.sequence());
        var events = cache.after(run.enterpriseId(), run.conversationId(), new StreamCursor(cursor.generation(), "9007199254740992"), Duration.ZERO, 10);
        assertEquals(cursor.sequence(), events.getFirst().sequence());
    }

    @Test
    void restartAndCounterRollbackCannotReuseAnOldReadPosition() {
        publish(ExecutionChange.replace(block("text", "原内容", "1", "running")));
        var old = cursor;
        redis.opsForValue().set(key() + ":sequence", "0");
        var mutation = codec.change(run, ExecutionChange.replace(block("text", "新增", "2", "running")), now);
        assertEquals("reset", cache.append(run.enterpriseId(), run.conversationId(), old, LiveEventCache.token(lease), mutation).status());
        redis.opsForHash().put(key() + ":meta", "server", "previous-process");
        assertTrue(cache.position(run.enterpriseId(), run.conversationId()).isEmpty());
        cursor = initialize(null, 0);
        assertNotEquals(old.generation(), cursor.generation());
        assertEquals("reset", cache.append(run.enterpriseId(), run.conversationId(), old, LiveEventCache.token(lease), mutation).status());
    }

    @Test
    void scriptFailureDoesNotExposePartiallyUpdatedSnapshot() {
        redis.opsForValue().set(key() + ":events", "模拟错误的键类型");
        var mutation = codec.change(run, ExecutionChange.replace(block("text", "未完成操作", "1", "running")), now);
        assertThrows(RuntimeException.class, () -> cache.append(run.enterpriseId(), run.conversationId(), cursor, LiveEventCache.token(lease), mutation));
        assertTrue(cache.position(run.enterpriseId(), run.conversationId()).isEmpty());
        assertTrue(cache.snapshot(run.enterpriseId(), run.conversationId()).isEmpty());
        var repaired = initialize(cursor.generation(), 0);
        assertNotEquals(cursor.generation(), repaired.generation());
        assertTrue(cache.snapshot(run.enterpriseId(), run.conversationId()).orElseThrow().objects().isEmpty());
    }

    @Test
    void endingAnExecutionPreventsLateRenewalAndTheNextLeaseRejectsOldWrites() {
        cache.freeze(run);
        assertFalse(cache.renew(run.enterpriseId(), run.conversationId(), cursor, lease, lease.until(), now));
        var mutation = codec.change(run, ExecutionChange.replace(block("text", "晚到内容", "1", "running")), now);
        assertEquals("lease_lost", cache.append(run.enterpriseId(), run.conversationId(), cursor, LiveEventCache.token(lease), mutation).status());
        var next = new JobLease(lease.id(), lease.enterpriseId(), lease.userId(), lease.runId(), "new-worker", 2, lease.until());
        assertTrue(cache.renew(run.enterpriseId(), run.conversationId(), cursor, next, next.until(), now));
        assertEquals("lease_lost", cache.append(run.enterpriseId(), run.conversationId(), cursor, LiveEventCache.token(lease), mutation).status());
        assertTrue(cache.append(run.enterpriseId(), run.conversationId(), cursor, LiveEventCache.token(next), mutation).accepted());
    }

    private StreamCursor initialize(String replace, long databaseVersion) {
        String owner = cache.claim(run.enterpriseId(), run.conversationId());
        try {
            return new StreamCursor(cache.initialize(run.enterpriseId(), run.conversationId(), owner, databaseVersion, run.id(), replace), "0");
        } finally {
            cache.release(run.enterpriseId(), run.conversationId(), owner);
        }
    }

    @Test
    void missingAccumulatedTextCannotBecomeAnEmptySnapshotWithANewerPosition() {
        publish(ExecutionChange.replace(block("text", "尚未保存的全文", "1", "running")));
        var before = cursor;
        redis.delete(key() + ":body:block:output:text");
        assertTrue(cache.snapshot(run.enterpriseId(), run.conversationId()).isEmpty());
        assertTrue(cache.position(run.enterpriseId(), run.conversationId()).isEmpty());
        cursor = initialize(null, 0);
        assertNotEquals(before.generation(), cursor.generation());
    }

    @Test
    void missingObjectDirectoryOrCounterForcesANewGeneration() {
        publish(ExecutionChange.replace(block("text", "原有正文", "1", "running")));
        redis.delete(key() + ":objects");
        assertTrue(cache.position(run.enterpriseId(), run.conversationId()).isEmpty());
        var before = cursor;
        cursor = initialize(null, 0);
        assertNotEquals(before.generation(), cursor.generation());
        redis.delete(key() + ":sequence");
        assertTrue(cache.position(run.enterpriseId(), run.conversationId()).isEmpty());
        assertNotEquals(cursor.generation(), initialize(null, 0).generation());
    }

    @Test
    void missingTextBeforeTheNextDeltaCannotSilentlyDiscardItsPrefix() {
        var first = block("text", "已输出的前文", "1", "running");
        publish(ExecutionChange.replace(first));
        redis.delete(key() + ":body:block:output:text");
        var next = first.update(first.text() + "后文", "running");
        var mutation = codec.change(run, ExecutionChange.delta(next, first.revision(), "后文"), now);
        var result = cache.append(run.enterpriseId(), run.conversationId(), cursor, LiveEventCache.token(lease), mutation);
        assertEquals("reset", result.status());
        assertFalse(redis.hasKey(key() + ":body:block:output:text"));
        assertTrue(cache.snapshot(run.enterpriseId(), run.conversationId()).isEmpty());
        assertTrue(cache.position(run.enterpriseId(), run.conversationId()).isEmpty());
    }

    @Test
    void measuresRetainedEventMemoryWithoutDiscardingUnfinishedText() {
        cache = new LiveEventCache(redis, codec, 10000);
        var block = block("text", "", "1", "running");
        publish(ExecutionChange.replace(block));
        String delta = "中文流式输出🙂";
        for (int i = 0; i < 1000; i++) {
            var next = block.update(block.text() + delta, "running");
            publish(ExecutionChange.delta(next, block.revision(), delta));
            block = next;
        }
        long before = memoryBytes();
        assertEquals(1001, redis.opsForStream().size(key() + ":events"));
        cache = new LiveEventCache(redis, codec, 64);
        publish(ExecutionChange.delta(block.update(block.text() + delta, "running"), block.revision(), delta));
        long after = memoryBytes();
        var snapshot = cache.snapshot(run.enterpriseId(), run.conversationId()).orElseThrow();
        assertEquals(delta.repeat(1001), snapshot.objects().get("block:output:text").path("block").path("text").asText());
        assertEquals(64, redis.opsForStream().size(key() + ":events"));
        assertTrue(after < before);
        System.out.printf("实时缓存容量测量：1001 条事件占用 %d 字节；清理到 64 条后占用 %d 字节，已校验累计全文完整。%n",
            before, after);
    }

    private long memoryBytes() {
        var keys = redis.keys(key() + ":*");
        assertNotNull(keys);
        var memory = new DefaultRedisScript<>("return redis.call('MEMORY', 'USAGE', KEYS[1])", Long.class);
        long total = 0;
        for (var stored : keys) {
            var bytes = redis.execute(memory, List.of(stored));
            if (bytes != null) {
                total += bytes;
            }
        }
        return total;
    }

    private void publish(ExecutionChange change) {
        var result = cache.append(run.enterpriseId(), run.conversationId(), cursor, LiveEventCache.token(lease), codec.change(run, change, now));
        assertTrue(result.accepted(), result.status());
        cursor = result.cursor();
    }

    private ContentBlock block(String id, String text, String revision, String status) {
        return new ContentBlock(id, "text", null, 1, revision, text, status, null, null, null, null, null, null);
    }

    private ExecutionEvent saved(ContentBlock block, long sequence) {
        return new ExecutionEvent(1, UUID.randomUUID().toString(), run.enterpriseId(), run.conversationId(), run.id(),
            Long.toString(sequence), now.toString(), "block.updated", Map.of("messageId", "output", "block", block));
    }

    private String key() {
        return cache.key(run.enterpriseId(), run.conversationId());
    }
}
