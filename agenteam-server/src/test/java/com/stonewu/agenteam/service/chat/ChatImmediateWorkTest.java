package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.configuration.chat.ChatWorkNotificationConfiguration;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatWorkRequested.Stage;
import com.stonewu.agenteam.model.chat.entity.ChatWorkRequested;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.wecom.WecomMemberAliases;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.TestOutputPaths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 定时扫描间隔设为一小时，使用隔离数据库和模拟平台发送验证提交后立即处理。 */
class ChatImmediateWorkTest extends ChatApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ChatWorkCoordinator work;
    @Autowired private ChatSendWorker sender;
    @Autowired private ChatNormalizationWorker receiver;
    @Autowired private StringRedisTemplate redis;
    @MockitoSpyBean private ChatPlatformRegistry platforms;
    @MockitoSpyBean private ChatConnectionRegistry connections;
    @MockitoSpyBean private WecomQuestionCardService questionCards;
    @MockitoSpyBean private WecomMemberAliases aliases;
    private ChatPlatformAdapter transport;
    private final AtomicInteger networkCalls = new AtomicInteger();
    private CountDownLatch submitted;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.worker-enabled", () -> false);
        registry.add("chat.poll-interval-ms", () -> 3_600_000);
        registry.add("chat.send-poll-interval-ms", () -> 3_600_000);
        registry.add("chat.routing-poll-interval-ms", () -> 3_600_000);
    }

    @BeforeEach
    void platform() {
        submitted = new CountDownLatch(1);
        networkCalls.set(0);
        transport = mock(ChatPlatformAdapter.class, CALLS_REAL_METHODS);
        doReturn(transport).when(platforms).adapter(anyString());
        doReturn(List.of(bot.getId())).when(connections).localBotIds();
        doNothing().when(aliases).prepare(any());
        when(transport.send(any(), any(), any())).thenAnswer(call -> {
            Runnable beforeSend = call.getArgument(2);
            beforeSend.run();
            int number = networkCalls.incrementAndGet();
            submitted.countDown();
            return accepted("platform-" + number);
        });
    }

    @AfterEach
    void stopWork() {
        configuration.setWorkerEnabled(false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"feishu", "wecom_aibot"})
    void commitSendsImmediatelyWhileRollbackAndUncommittedWritesSendNothing(String provider) throws Exception {
        var group = enableGroup();
        provider(provider, group);
        var transactions = new TransactionTemplate(transactionManager);
        configuration.setWorkerEnabled(true);
        transactions.executeWithoutResult(status -> {
            queue(group, "rollback");
            status.setRollbackOnly();
        });
        assertEquals(0, outgoing().selectCount(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)));
        var queued = transactions.execute(status -> {
            var row = queue(group, "committed");
            try {
                assertFalse(submitted.await(200, TimeUnit.MILLISECONDS), "数据库尚未提交，不能提前发送确认");
            } catch (InterruptedException failure) {
                throw new IllegalStateException(failure);
            }
            return row;
        });
        long committed = System.nanoTime();
        await(() -> "sent".equals(outgoing().selectById(queued.getId()).getStatus()));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - committed);
        assertEquals(1, networkCalls.get());
        assertEquals(1, outgoing().selectById(queued.getId()).getAttemptCount());
        var path = TestOutputPaths.path("chat-immediate", provider + "-commit.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.writeValueAsString(Map.of("provider", provider, "commitToSavedSendResultMs", millis,
            "transport", "模拟平台发送，隔离数据库与Redis", "scheduledIntervalMs", 3_600_000)));
    }

    @Test
    void ordinaryMessageIsNormalizedAndRoutedWithoutWaitingForTheScheduler() throws Exception {
        enableGroup();
        configuration.setWorkerEnabled(true);
        String receipt = ingress.accept(enterprise, bot.getId(), 1L, message("route-now", "route-now", "user", "请协助整理内容"));
        await(() -> "normalized".equals(receipts().selectById(receipt).getStatus()));
        await(() -> messages().selectCount(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getRoutingStatus, "awaiting_binding")) == 1);
        await(() -> outgoing().selectCount(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getStatus, "sent")) == 1);
        assertEquals(1, networkCalls.get());
    }

    @Test
    void storedReceiptsAndMissedNotificationsAreRecoveredWithoutDuplicateProcessing() throws Exception {
        provider("wecom_aibot", null);
        configuration.setWorkerEnabled(true);
        var event = control("enter-now", "enter_chat");
        String id = ingress.accept(enterprise, bot.getId(), 1L, event);
        await(() -> "ignored".equals(receipts().selectById(id).getStatus()));
        assertEquals(1, receipts().selectById(id).getAttemptCount());
        assertEquals(id, ingress.accept(enterprise, bot.getId(), 1L, event));
        assertFalse(receiver.runOnce());
        assertEquals(1, receipts().selectById(id).getAttemptCount());
        configuration.setWorkerEnabled(false);
        String pending = ingress.accept(enterprise, bot.getId(), 1L, control("missed-wakeup", "enter_chat"));
        assertEquals("received", receipts().selectById(pending).getStatus());
        configuration.setWorkerEnabled(true);
        receiver.poll();
        await(() -> "ignored".equals(receipts().selectById(pending).getStatus()));
        assertEquals(1, receipts().selectById(pending).getAttemptCount());
    }

    @Test
    void answeredQuestionIsTerminalButTemporaryFailureStillRetries() throws Exception {
        provider("wecom_aibot", null);
        doThrow(new ApiException(HttpStatus.CONFLICT, "QUESTION_ALREADY_ANSWERED", "这个问题已经回答。"))
            .when(questionCards).accept(any(), any(), any());
        configuration.setWorkerEnabled(true);
        String rejected = ingress.accept(enterprise, bot.getId(), 1L, card("answered-click"));
        await(() -> "ignored".equals(receipts().selectById(rejected).getStatus()));
        var saved = receipts().selectById(rejected);
        assertEquals("QUESTION_ALREADY_ANSWERED", saved.getErrorCode());
        assertEquals(1, saved.getAttemptCount());
        assertNull(saved.getNextAttemptAt());
        verify(questionCards, times(1)).accept(any(), any(), any());
        assertFalse(receiver.runOnce());
        doThrow(new IllegalStateException("合成暂时不可用"))
            .when(questionCards).accept(any(), any(), any());
        String temporary = ingress.accept(enterprise, bot.getId(), 1L, card("temporary-click"));
        await(() -> "retry_wait".equals(receipts().selectById(temporary).getStatus()));
        assertNotNull(receipts().selectById(temporary).getNextAttemptAt());
        assertEquals(1, receipts().selectById(temporary).getAttemptCount());
    }

    @Test
    void finishingPredecessorImmediatelyUnblocksFollowerAndPreservesRateBackoff() throws Exception {
        var group = enableGroup();
        var first = queue(group, "first");
        var lease = sending.claim("first-process").orElseThrow();
        assertEquals(first.getId(), lease.outboundId());
        var next = queue(group, "next");
        outgoing().update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, next.getId())
            .set(ChatOutboundMessageRow::getDependsOnOutboundId, first.getId()));
        assertTrue(sending.claim("second-process").isEmpty());
        assertEquals("CHAT_PREDECESSOR_PENDING", outgoing().selectById(next.getId()).getErrorCode());
        var limited = queue(group, "rate-limited");
        var retryAt = Instant.now().plusSeconds(20);
        outgoing().update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, limited.getId())
            .set(ChatOutboundMessageRow::getDependsOnOutboundId, first.getId()).set(ChatOutboundMessageRow::getStatus, "retry_wait")
            .set(ChatOutboundMessageRow::getErrorCode, "CHAT_RATE_LIMITED").set(ChatOutboundMessageRow::getNextAttemptAt, retryAt));
        sending.begin(lease);
        configuration.setWorkerEnabled(true);
        sending.complete(lease, accepted("first-platform"));
        await(() -> "sent".equals(outgoing().selectById(next.getId()).getStatus()));
        assertEquals(1, networkCalls.get());
        var stillWaiting = outgoing().selectById(limited.getId());
        assertEquals("retry_wait", stillWaiting.getStatus());
        assertEquals("CHAT_RATE_LIMITED", stillWaiting.getErrorCode());
        assertTrue(stillWaiting.getNextAttemptAt().isAfter(Instant.now()));
    }

    @Test
    void periodicFallbackSendsPersistedWorkAfterAMissedWakeup() throws Exception {
        var group = enableGroup();
        var queued = queue(group, "saved-before-restart");
        assertEquals(0, networkCalls.get());
        configuration.setWorkerEnabled(true);
        sender.poll();
        await(() -> "sent".equals(outgoing().selectById(queued.getId()).getStatus()));
        work.wake(Stage.SEND);
        assertFalse(sender.runOnce());
        assertEquals(1, networkCalls.get());
    }

    @Test
    void redisNotificationWakesTheConnectionOwnerOnAnotherInstance() throws Exception {
        var config = new ChatConfiguration();
        config.setWorkerEnabled(true);
        var origin = mock(ChatSendWorker.class);
        var owner = mock(ChatSendWorker.class);
        var received = new CountDownLatch(1);
        when(owner.runOnce()).thenAnswer(call -> {
            received.countDown();
            return false;
        });
        String namespace = "isolated-chat-" + UUID.randomUUID();
        try (var first = ChatWorkCoordinatorTest.create(config, mock(ChatNormalizationWorker.class), mock(ChatRoutingWorker.class), origin, redis, namespace);
             var second = ChatWorkCoordinatorTest.create(config, mock(ChatNormalizationWorker.class), mock(ChatRoutingWorker.class), owner, redis, namespace)) {
            var wiring = new ChatWorkNotificationConfiguration();
            var executor = wiring.executor();
            var subscriber = wiring.chatWorkNotifications(redis.getConnectionFactory(), second, executor);
            try {
                subscriber.afterPropertiesSet();
                subscriber.start();
                first.committed(new ChatWorkRequested(Stage.SEND));
                assertTrue(received.await(5, TimeUnit.SECONDS), "持有连接的另一实例应收到通知并发送");
                verify(owner, times(1)).runOnce();
                verify(origin, times(1)).runOnce();
            } finally {
                subscriber.destroy();
                executor.shutdownNow();
            }
        }
    }

    private ChatOutboundMessageRow queue(ChatConversationRow group, String key) {
        return outbound.text(enterprise, group.getId(), bot.getId(), null, null, key, "probe", "合成确认消息", null);
    }

    private ChatOutboundMessageMapper outgoing() {
        return databaseAccess.mapper(ChatOutboundMessageMapper.class);
    }

    private void provider(String code, ChatConversationRow group) {
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()).set(EnterpriseIntegrationRow::getProviderCode, code));
        if (group != null) {
            databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
                .eq(ChatConversationRow::getId, group.getId()).set(ChatConversationRow::getPlatformCode, code));
        }
    }

    private ObjectNode control(String id, String type) {
        var frame = json.createObjectNode().put("cmd", "aibot_event_callback");
        frame.putObject("headers").put("req_id", id);
        var body = frame.putObject("body").put("chatid", externalChat).put("chattype", "group").put("msgid", id);
        body.putObject("from").put("userid", "synthetic-user");
        body.putObject("event").put("eventtype", type);
        return frame;
    }

    private ObjectNode card(String id) {
        var frame = control(id, "template_card_event");
        frame.withObject("/body/event").putObject("template_card_event")
            .put("event_key", "question_option_1").put("task_id", "nonexistent-source");
        return frame;
    }

    private static ChatPlatformAdapter.SendReceipt accepted(String id) {
        return new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "0", id,
            null, null, null, 200, "test-trace"), null);
    }
}
