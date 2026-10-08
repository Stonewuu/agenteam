package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentEventRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.service.execution.ConversationEventService;
import com.stonewu.agenteam.service.execution.ExecutionEventPublisher;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用实际数据库和 Redis 验证正式事件连接，控制事件不占用正文序号。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationEventApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private ExecutionEventPublisher publisher;

    @Autowired
    private ExecutionEventCodec codec;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private ExecutionEventCache cache;

    @MockitoSpyBean
    private ExecutionEventMapper eventRecords;

    @Autowired
    private ConversationEventService connections;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void replayUsesDecimalIdsAndReadyHasNoIdThenConnectionCloses() throws Exception {
        var accepted = submitAndCancel();
        String conversation = accepted.path("conversationId").asText();
        var saved = events.after(enterprise, conversation, 0, 100);
        var frames = frames(read(conversation, "0", "0"));
        assertEquals(saved.size() + 1, frames.size());
        for (int i = 0; i < saved.size(); i++) {
            var frame = frames.get(i);
            assertEquals(saved.get(i).sequence(), frame.get("id"));
            var value = json.readTree(frame.get("data"));
            schemas.validate("ExecutionEvent", value);
            assertEquals(saved.get(i).eventId(), value.path("eventId").asText());
        }
        var ready = frames.getLast();
        assertEquals("stream.ready", ready.get("event"));
        assertFalse(ready.containsKey("id"));
        assertEquals(saved.getLast().sequence(), json.readTree(ready.get("data")).path("lastSequence").asText());
        var resumed = frames(read(conversation, saved.getLast().sequence(), null));
        assertEquals(1, resumed.size());
        assertEquals("stream.ready", resumed.getFirst().get("event"));
    }

    @Test
    void rejectsOldRedisIdsConflictingHeadersAndForeignConversation() throws Exception {
        String conversation = submitAndCancel().path("conversationId").asText();
        for (String after : List.of("123456-0", "01", "-1", "9223372036854775808", "", "1.0")) {
            mvc.perform(get(eventPath(conversation)).cookie(cookie).param("after", after)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(eventPath(conversation)).cookie(cookie).param("after", "1").header("Last-Event-ID", "2")).andExpect(status().isBadRequest());
        String other = provisioning.create("无法读取其他企业的对话", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        mvc.perform(get("/api/v1/enterprises/" + other + "/conversations/" + conversation + "/events").cookie(cookie)).andExpect(status().isNotFound());
        assertReset(conversation, "999", "sequence_invalid");
    }

    @Test
    void redisPublishingIsRepeatableAndCacheLossOrFailureStillReadsDatabase() throws Exception {
        String conversation = submitAndCancel().path("conversationId").asText();
        int savedCount = events.after(enterprise, conversation, 0, 100).size();
        publisher.publish(enterprise, conversation);
        publisher.publish(enterprise, conversation);
        String key = cache.key(enterprise, conversation);
        assertEquals(savedCount, redis.opsForStream().size(key));
        assertTrue(redis.getExpire(key) > 86300);
        assertEquals(0, events.unpublished(enterprise, conversation, 100).size());
        var expected = frames(read(conversation, "0", null));
        redis.delete(key);
        assertEquals(expected, frames(read(conversation, "0", null)));
        doThrow(new RedisConnectionFailureException("仅模拟事件缓存连接失败")).when(cache).after(anyString(), anyString(), anyLong(), anyInt());
        try {
            assertEquals(expected, frames(read(conversation, "0", null)));
        } finally {
            doCallRealMethod().when(cache).after(anyString(), anyString(), anyLong(), anyInt());
        }
        assertEquals(savedCount, count("agent_event"));
        assertEquals(2, count("agent_message"));
    }

    @Test
    void corruptCacheFallsBackButMissingOrCorruptDatabaseEventsRequestSnapshot() throws Exception {
        String conversation = submitAndCancel().path("conversationId").asText();
        publisher.publish(enterprise, conversation);
        var expected = frames(read(conversation, "0", null));
        String key = cache.key(enterprise, conversation);
        redis.delete(key);
        redis.opsForStream().add(StreamRecords.string(Map.of("data", "{}", "hash", "invalid")).withStreamKey(key).withId(RecordId.of("1-0")));
        assertEquals(expected, frames(read(conversation, "0", null)));
        redis.delete(key);
        databaseAccess.mapper(ExecutionEventSqlMapper.class).delete(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getConversationId, (conversation)).eq(AgentEventRow::getConversationSequence, 2));
        assertReset(conversation, "0", "data_incomplete");
        databaseAccess.mapper(ExecutionEventSqlMapper.class).update(new LambdaUpdateWrapper<AgentEventRow>().eq(AgentEventRow::getConversationId, (conversation)).eq(AgentEventRow::getConversationSequence, 3).set(AgentEventRow::getPayloadHash, "invalid"));
        assertReset(conversation, "2", "data_incomplete");
    }

    @Test
    void expiryRemovesEventsButKeepsFullMessagesAndSignalsSnapshot() throws Exception {
        String conversation = submitAndCancel().path("conversationId").asText();
        databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getConversationId, (conversation)).set(AgentRunRow::getFinishedAt, (Timestamp.from(Instant.now().minusSeconds(8 * 86400)))));
        publisher.removeExpired();
        assertEquals(0, count("agent_event"));
        assertEquals(2, count("agent_message"));
        assertReset(conversation, "0", "events_expired");
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ConversationSnapshot", snapshot);
        assertFalse(snapshot.at("/messages/0/content").asText().isBlank());
        assertEquals("stream.ready", frames(read(conversation, snapshot.path("lastSequence").asText(), null)).getFirst().get("event"));
    }

    @Test
    void sequenceBeyondJavascriptIntegerRangeRemainsExactInRedisAndSse() throws Exception {
        String conversation = submitAndCancel().path("conversationId").asText();
        var original = events.after(enterprise, conversation, 0, 100);
        long offset = 9_007_199_254_740_992L;
        for (var value : original.reversed()) {
            long sequence = offset + Long.parseLong(value.sequence());
            var shifted = new ExecutionEvent(value.protocolVersion(), value.eventId(), value.enterpriseId(), value.conversationId(), value.runId(), Long.toString(sequence), value.createdAt(), value.type(), value.payload());
            var encoded = codec.encode(shifted);
            databaseAccess.mapper(ExecutionEventSqlMapper.class).update(new LambdaUpdateWrapper<AgentEventRow>().eq(AgentEventRow::getEventId, (value.eventId())).set(AgentEventRow::getConversationSequence, (sequence)).set(AgentEventRow::getPayloadJson, (encoded.data())).set(AgentEventRow::getPayloadHash, (encoded.hash())));
        }
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)).set(AgentConversationRow::getLastSequence, (offset + original.size())));
        publisher.publish(enterprise, conversation);
        publisher.publish(enterprise, conversation);
        var frames = frames(read(conversation, Long.toString(offset), null));
        assertEquals(Long.toString(offset + 1), frames.getFirst().get("id"));
        assertEquals(offset + original.size(), cache.last(enterprise, conversation).sequence());
        assertEquals(original.size() + 1, frames.size());
    }

    @Test
    void readySeparatesReplayFromLiveEventsAndDisconnectDoesNotCancelRun() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String conversation = accepted.path("conversationId").asText();
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        connections.connect(actor, "订阅建立后立即断开", conversation, "0", null).take(1).blockLast(Duration.ofSeconds(5));
        assertEquals("queued", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        var pending = mvc.perform(get(eventPath(conversation)).cookie(cookie).param("after", "0")).andExpect(request().asyncStarted()).andReturn();
        await(() -> new String(pending.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8).contains("stream.ready"));
        write(base() + "/runs/" + accepted.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        pending.getAsyncResult(5000);
        var frames = frames(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("stream.ready", frames.get(3).get("event"));
        assertFalse(frames.get(3).containsKey("id"));
        assertEquals("3", json.readTree(frames.get(3).get("data")).path("lastSequence").asText());
        assertEquals("run.cancelled", frames.getLast().get("event"));
    }

    @Test
    void failureAfterRedisAcknowledgementCanRetryWithoutDuplicatingEvents() throws Exception {
        String conversation = submitAndCancel().path("conversationId").asText();
        int size = count("agent_event");
        doThrow(new DataAccessResourceFailureException("仅模拟分发结果暂时无法保存")).when(eventRecords).publishedThrough(anyString(), anyString(), anyLong(), any(Instant.class));
        try {
            assertThrows(DataAccessResourceFailureException.class, () -> publisher.publish(enterprise, conversation));
        } finally {
            doCallRealMethod().when(eventRecords).publishedThrough(anyString(), anyString(), anyLong(), any(Instant.class));
        }
        assertEquals(size, events.unpublished(enterprise, conversation, 100).size());
        assertEquals(size, redis.opsForStream().size(cache.key(enterprise, conversation)));
        publisher.publish(enterprise, conversation);
        assertEquals(size, redis.opsForStream().size(cache.key(enterprise, conversation)));
        assertEquals(0, events.unpublished(enterprise, conversation, 100).size());
    }

    @Test
    void replayingAnOlderTerminalDoesNotCloseTheCurrentRunConnection() throws Exception {
        var previous = submitAndCancel();
        String conversation = previous.path("conversationId").asText();
        var current = data(write(base() + "/conversations/" + conversation + "/messages", input("继续当前任务"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var pending = mvc.perform(get(eventPath(conversation)).cookie(cookie).param("after", "0")).andExpect(request().asyncStarted()).andReturn();
        await(() -> new String(pending.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8).contains("stream.ready"));
        assertThrows(IllegalStateException.class, () -> pending.getAsyncResult(100));
        write(base() + "/runs/" + current.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        pending.getAsyncResult(5000);
        var received = frames(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(2, received.stream().filter(frame -> "run.cancelled".equals(frame.get("event"))).count());
        assertEquals(current.path("runId"), json.readTree(received.getLast().get("data")).path("runId"));
    }

    private JsonNode submitAndCancel() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        write(base() + "/runs/" + accepted.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        return accepted;
    }

    private String eventPath(String conversation) {
        return base() + "/conversations/" + conversation + "/events";
    }

    private String read(String conversation, String after, String lastEventId) throws Exception {
        var request = get(eventPath(conversation)).cookie(cookie);
        if (after != null) {
            request.param("after", after);
        }
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        MvcResult pending = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(5000);
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Map<String, String>> frames(String body) {
        var values = new ArrayList<Map<String, String>>();
        for (String frame : body.replace("\r", "").split("\n\n")) {
            Map<String, String> fields = new HashMap<>();
            for (String line : frame.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    fields.put(line.substring(0, colon), line.substring(colon + 1).stripLeading());
                }
            }
            if (!fields.isEmpty()) {
                values.add(fields);
            }
        }
        return values;
    }

    private void assertReset(String conversation, String after, String reason) throws Exception {
        var frames = frames(read(conversation, after, null));
        assertEquals(1, frames.size());
        assertEquals("stream.reset", frames.getFirst().get("event"));
        assertFalse(frames.getFirst().containsKey("id"));
        var data = json.readTree(frames.getFirst().get("data"));
        schemas.validate("StreamReset", data);
        assertEquals(reason, data.path("reason").asText());
    }
}
