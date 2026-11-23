package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.CommittedConversationChanges;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.execution.ConversationChangePublisher;
import com.stonewu.agenteam.service.execution.ConversationEventService;
import com.stonewu.agenteam.service.execution.ConversationSnapshotService;
import com.stonewu.agenteam.service.execution.ExecutionPresentationFactory;
import com.stonewu.agenteam.service.execution.ExecutionMessageWriter;
import com.stonewu.agenteam.service.execution.LiveEventDelivery;
import com.stonewu.agenteam.service.auth.ConnectionAuthorization;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证业务事务版本、Redis 重读，以及通知遗漏后从业务结果恢复。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationEventApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private ConversationSnapshotService snapshots;
    @Autowired
    private ConversationEventService connections;
    @Autowired
    private ConversationChangePublisher changes;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private ExecutionPresentationFactory presentation;
    @Autowired
    private ExecutionMessageWriter writer;
    @MockitoSpyBean
    private Clock clock;
    @MockitoSpyBean
    private ConnectionAuthorization authorization;
    @MockitoSpyBean
    private LiveEventDelivery delivery;
    @MockitoSpyBean
    private ExecutionMessageMapper messages;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void oneSubmissionUsesOneBusinessVersionAndLegacyReadersReloadTheSnapshot() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        String conversation = accepted.path("conversationId").asText();
        assertEquals("1", accepted.path("lastSequence").asText());
        assertEquals(1, conversationRecords.lastSequence(enterprise, conversation));
        assertEquals(0, count("agent_event"));
        var before = snapshots.read(actor(), conversation);
        assertEquals(2, before.protocolVersion());
        assertNotNull(before.streamCursor());
        var reset = connections.connect(actor(), "legacy-reader", conversation, "0", null).blockFirst();
        assertEquals("stream.reset", reset.event());
        for (String invalid : List.of("-1", "01", "1e2", "9223372036854775808")) {
            mvc.perform(get(base() + "/conversations/" + conversation + "/events").cookie(cookie)
                .param("after", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(base() + "/conversations/" + conversation + "/events").cookie(cookie)
            .param("after", "1").header("Last-Event-ID", "2")).andExpect(status().isBadRequest());
        write(base() + "/runs/" + accepted.path("runId").asText() + "/cancel", Map.of(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted());
        var frames = connections.connect(actor(), "reader", conversation, before.streamCursor().sequence(), null,
            before.streamCursor().generation()).collectList().block(Duration.ofSeconds(10));
        assertNotNull(frames);
        assertTrue(frames.stream().anyMatch(frame -> "run.cancelled".equals(frame.event()) || "stream.reset".equals(frame.event())));
        assertNull(snapshots.read(actor(), conversation).activeRun());
        assertEquals(0, count("agent_event"));
    }

    @Test
    void rollbackRestoresBusinessDataAndVersionWithoutPublishingAnything() throws Exception {
        var fixture = start();
        try {
            long version = conversationRecords.lastSequence(enterprise, fixture.run().conversationId());
            var before = liveEventCache.position(enterprise, fixture.run().conversationId()).orElseThrow();
            clearInvocations(delivery);
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                messages.save(enterprise, fixture.run().outputMessageId(), "不能出现的内容", List.of(), "streaming", version, Instant.now());
                changes.append(fixture.run(), "message.created", messages.find(enterprise, fixture.run().conversationId(),
                    fixture.run().outputMessageId()).orElseThrow(), Instant.now());
                transaction.setRollbackOnly();
            });
            assertEquals(version, conversationRecords.lastSequence(enterprise, fixture.run().conversationId()));
            assertEquals("", messages.find(enterprise, fixture.run().conversationId(), fixture.run().outputMessageId()).orElseThrow().content());
            verify(delivery, never()).committed(any(CommittedConversationChanges.class));
            assertEquals(before.cursor(), liveEventCache.position(enterprise, fixture.run().conversationId()).orElseThrow().cursor());
            assertEquals(0, count("agent_event"));
            var block = new ContentBlock("saved-round", "text", null, 1, "1", "完整本轮", "completed",
                null, null, null, null, null, null);
            var content = List.of(new ExecutionChange(block, null, "0", null, null, null));
            String batch = UUID.randomUUID().toString();
            writer.save(fixture.lease(), batch, content);
            writer.save(fixture.lease(), batch, content);
            assertEquals(version + 1, conversationRecords.lastSequence(enterprise, fixture.run().conversationId()));
            assertThrows(IllegalStateException.class, () -> writer.save(fixture.lease(), batch,
                List.of(new ExecutionChange(block.update("不同内容", "completed"), null, "1", null, null, null))));
            assertEquals("完整本轮", messages.find(enterprise, fixture.run().conversationId(), fixture.run().outputMessageId()).orElseThrow().content());
        } finally {
            lifecycle.finish(fixture.lease(), "completed", null, null);
        }
    }

    @Test
    void aConnectedReaderDetectsAMissedCompletionWithoutAnotherSnapshotRequest() throws Exception {
        var fixture = start();
        var cursor = snapshots.read(actor(), fixture.run().conversationId()).streamCursor();
        doNothing().when(delivery).committed(any(CommittedConversationChanges.class));
        lifecycle.finish(fixture.lease(), "completed", null, null);
        long now = System.currentTimeMillis();
        doReturn(now, now, now + 16000).when(clock).millis();
        doReturn(true).when(authorization).allowed(eq("reader"), any(), eq("conversation.view"));
        try {
            var reset = connections.connect(actor(), "reader", fixture.run().conversationId(), cursor.sequence(), null,
                cursor.generation()).filter(frame -> "stream.reset".equals(frame.event())).next().block(Duration.ofSeconds(5));
            assertNotNull(reset);
            assertEquals("database_changed", ((Map<?, ?>) reset.data()).get("reason"));
            assertNull(snapshots.read(actor(), fixture.run().conversationId()).activeRun());
        } finally {
            doCallRealMethod().when(clock).millis();
            doCallRealMethod().when(delivery).committed(any(CommittedConversationChanges.class));
        }
    }

    @Test
    void eachRoundSavesBeforeTheNextAndMissedNotificationsPreserveUnfinishedOutput() throws Exception {
        var fixture = start();
        try (var frames = presentation.open(fixture.run(), fixture.lease())) {
            frames.accept(event("AGENT_START", "agent", null, null));
            frames.flush();
            var cursor = snapshots.read(actor(), fixture.run().conversationId()).streamCursor();
            doNothing().when(delivery).committed(any(CommittedConversationChanges.class));
            clearInvocations(messages);
            frames.accept(event("MODEL_CALL_START", "first", null, null));
            frames.accept(event("TEXT_BLOCK_START", "first", "one", null));
            frames.accept(event("TEXT_BLOCK_DELTA", "first", "one", "第一轮完整内容"));
            frames.accept(event("TEXT_BLOCK_END", "first", "one", null));
            frames.accept(toolEvent("TOOL_CALL_START", null));
            frames.accept(toolEvent("TOOL_CALL_DELTA", "{\"path\":\""));
            for (int index = 0; index < 100; index++) {
                frames.accept(toolEvent("TOOL_CALL_DELTA", "目录"));
            }
            frames.accept(toolEvent("TOOL_CALL_DELTA", "\"}"));
            frames.accept(toolEvent("TOOL_CALL_END", null));
            verify(messages, never()).save(eq(enterprise), eq(fixture.run().outputMessageId()), anyString(), anyList(), anyString(), anyLong(), any());
            frames.accept(event("MODEL_CALL_END", "first", null, null));
            frames.flush();
            assertEquals("第一轮完整内容", messages.find(enterprise, fixture.run().conversationId(), fixture.run().outputMessageId()).orElseThrow().content());
            verify(messages, times(1)).save(eq(enterprise), eq(fixture.run().outputMessageId()), anyString(), anyList(), anyString(), anyLong(), any());
            frames.accept(event("MODEL_CALL_START", "second", null, null));
            frames.accept(event("TEXT_BLOCK_START", "second", "two", null));
            frames.accept(event("TEXT_BLOCK_DELTA", "second", "two", "第二轮仍在生成"));
            frames.flush();
            var recovered = snapshots.read(actor(), fixture.run().conversationId());
            String text = recovered.messages().stream().filter(message -> message.id().equals(fixture.run().outputMessageId()))
                .findFirst().orElseThrow().content();
            assertEquals("第一轮完整内容\n\n第二轮仍在生成", text);
            verify(messages, times(1)).save(eq(enterprise), eq(fixture.run().outputMessageId()), anyString(), anyList(), anyString(), anyLong(), any());
            var reset = connections.connect(actor(), "missed-notification", fixture.run().conversationId(), cursor.sequence(),
                null, cursor.generation()).filter(frame -> "stream.reset".equals(frame.event())).next().block(Duration.ofSeconds(5));
            assertNotNull(reset);
            frames.accept(event("TEXT_BLOCK_END", "second", "two", null));
            frames.accept(event("MODEL_CALL_END", "second", null, null));
            frames.flush();
            verify(messages, times(2)).save(eq(enterprise), eq(fixture.run().outputMessageId()), anyString(), anyList(), anyString(), anyLong(), any());
            assertEquals(text, messages.find(enterprise, fixture.run().conversationId(), fixture.run().outputMessageId()).orElseThrow().content());
            assertEquals(0, count("agent_event"));
        } finally {
            doCallRealMethod().when(delivery).committed(any(CommittedConversationChanges.class));
            lifecycle.finish(fixture.lease(), "completed", null, null);
        }
    }

    private record Fixture(RunRecord run, JobLease lease) {
    }

    private Fixture start() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        var lease = lifecycle.claim("event-test-" + UUID.randomUUID()).orElseThrow();
        assertEquals(accepted.path("runId").asText(), lease.runId());
        var run = lifecycle.start(lease).orElseThrow();
        delivery.recover(run);
        return new Fixture(run, lease);
    }

    private AuthContext actor() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }

    private AgentStreamEvent event(String type, String reply, String block, String delta) {
        return new AgentStreamEvent(UUID.randomUUID().toString(), type, null, reply, block, delta, null, null,
            null, null, null, Instant.now().toString(), Map.of(), Map.of());
    }

    private AgentStreamEvent toolEvent(String type, String delta) {
        return new AgentStreamEvent(UUID.randomUUID().toString(), type, null, "first", null, delta, "call", "list_files",
            null, null, null, Instant.now().toString(), Map.of(), Map.of());
    }
}
