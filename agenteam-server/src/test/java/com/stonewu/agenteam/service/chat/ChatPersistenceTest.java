package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationBotMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundAttemptMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundAttemptRow;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.integration.ChannelRateLimiter;
import com.stonewu.agenteam.model.chat.entity.ChatReceiptLease;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实 MySQL 事务中验证接收、发送和过期工作不能提交。 */
class ChatPersistenceTest extends ChatApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Test
    void wecomControlEventCannotCreateAnEnabledConversation() {
        configuration.getWecomAibot().setEnabled(true);
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()).set(EnterpriseIntegrationRow::getProviderCode, "wecom_aibot")
            .set(EnterpriseIntegrationRow::getIdentityScope, "bot").set(EnterpriseIntegrationRow::getExternalTenantId, null)
            .set(EnterpriseIntegrationRow::getLoginEnabled, false).set(EnterpriseIntegrationRow::getMessagingEnabled, false));
        var event = json.createObjectNode().put("cmd", "aibot_event_callback");
        event.putObject("headers").put("req_id", "enter-chat-event");
        event.putObject("body").put("chatid", externalChat).put("chattype", "group").putObject("event").put("eventtype", "enter_chat");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        assertNull(conversation());
        assertEquals(0, messageCount());
    }

    @Test
    void applicationDisabledAfterNetworkSendDoesNotEraseTheAcceptedPlatformResult() {
        var group = enableGroup();
        var queued = outbound.text(enterprise, group.getId(), bot.getId(), null, null, "accepted-before-disable", "send_probe", "合成验证消息", null);
        var lease = sending.claim("accepted-test").orElseThrow();
        assertEquals(queued.getId(), lease.outboundId());
        sending.begin(lease);
        String echo = ingress.accept(enterprise, bot.getId(), 1L,
            message("early-bot-echo", "accepted-platform-id", "app", "合成验证消息"));
        assertEquals("ignored", receipts().selectById(echo).getStatus());
        assertEquals(0, messageCount(), "机器人回调早于本地发送结果时仍不能形成用户任务输入");
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()).set(EnterpriseIntegrationRow::getStatus, "disabled"));
        sending.complete(lease, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "0", "accepted-platform-id", null, null, null, 200, "trace"), null));
        var saved = databaseAccess.mapper(ChatOutboundMessageMapper.class).selectById(queued.getId());
        assertEquals("sent", saved.getStatus());
        assertEquals("accepted-platform-id", saved.getPlatformMessageId());
    }
    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }


    @Test
    void duplicateAndConcurrentDeliveriesCreateOneReceiptAndMessage() throws Exception {
        enableGroup();
        var event = message("event-one", "message-one", "user", "这段薪资正文只能加密保存");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> ingress.accept(enterprise, bot.getId(), 1L, event));
            var second = executor.submit(() -> ingress.accept(enterprise, bot.getId(), 1L, event));
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        normalizeAll();
        assertEquals(1, receiptCount());
        assertEquals(1, messageCount());
        var saved = messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise));
        assertFalse(saved.getContentEncrypted().contains("薪资正文"));
        assertEquals("这段薪资正文只能加密保存", cipher.decrypt(enterprise, "message", saved.getId(), saved.getContentEncrypted()).path("text").asText());
        ((ObjectNode) event.path("header")).put("event_id", "different-delivery-same-message");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        assertEquals(2, receiptCount());
        assertEquals(1, messageCount());
    }

    @Test
    void unmentionedUndiscoveredGroupSavesNoMessageBodyAndDoesNotExecute() {
        var event = message("unknown", "m", "user", "未启用群的私密正文");
        event.withObject("/event/message").remove("mentions");
        String id = ingress.accept(enterprise, bot.getId(), 1L, event);
        var receipt = receipts().selectById(id);
        assertEquals("ignored", receipt.getStatus());
        assertFalse(cipher.decrypt(enterprise, "receipt", id, receipt.getPayloadEncrypted()).toString().contains("私密正文"));
        var discovered = conversation();
        assertEquals("discovered", discovered.getStatus());
        assertEquals(0, messageCount());
    }

    @Test
    void revokedConnectionAndFeatureSwitchRejectBeforeWriting() {
        var event = message("reject", "reject", "user", "不应保存");
        assertThrows(ApiException.class, () -> ingress.accept(enterprise, bot.getId(), null, event));
        assertThrows(ApiException.class, () -> ingress.accept(enterprise, bot.getId(), 0L, event));
        configuration.setEnabled(false);
        assertThrows(ApiException.class, () -> ingress.accept(enterprise, bot.getId(), 1L, event));
        configuration.setEnabled(true);
        assertEquals(0, receiptCount());
    }

    @Test
    void workerLeaseCanBeReclaimedAndOldWorkerCannotComplete() {
        enableGroup();
        String id = ingress.accept(enterprise, bot.getId(), 1L, message("lease", "lease", "user", "任务"));
        var old = normalization.claim("old-worker").orElseThrow();
        assertEquals(id, old.receiptId());
        receipts().update(new LambdaUpdateWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getId, id)
            .set(ChatInboundReceiptRow::getLeaseUntil, Instant.now().minusSeconds(1)));
        var current = normalization.claim("new-worker").orElseThrow();
        assertTrue(current.version() > old.version());
        normalization.normalize(old);
        assertEquals(0, messageCount());
        normalization.normalize(current);
        assertEquals(1, messageCount());
        normalization.failed(old, "STALE");
        assertEquals("normalized", receipts().selectById(id).getStatus());
    }

    @Test
    void leaveDisablesAllDirectionsAndLateJoinOrMessageCannotRestoreThem() {
        var group = enableGroup();
        var leftAt = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        conversations.left(enterprise, group.getId(), bot.getId(), false, leftAt);
        conversations.observe(bot, bots.application(bot), externalChat, null, "group", "platform_event", leftAt.minusSeconds(2));
        ingress.accept(enterprise, bot.getId(), 1L, message("late", "late", "user", "晚到消息"));
        normalizeAll();
        var member = conversations.member(enterprise, group.getId(), bot.getId());
        assertEquals("left", member.getMembershipStatus());
        assertFalse(member.getSendEnabled());
        assertFalse(member.getReceiveEnabled());
        assertFalse(member.getCollaborationEnabled());
        assertEquals(0, messageCount());
        conversations.observe(bot, bots.application(bot), externalChat, null, "group", "platform_event", leftAt.plusSeconds(1));
        assertEquals("present", conversations.member(enterprise, group.getId(), bot.getId()).getMembershipStatus());
        assertFalse(conversations.member(enterprise, group.getId(), bot.getId()).getReceiveEnabled(), "重新入群仍需重新启用岗位");
    }

    @Test
    void resourceMetadataIsEncryptedAndDuplicateMessageDoesNotDuplicateResources() {
        enableGroup();
        var event = message("resource", "resource", "user", "");
        ((ObjectNode) event.at("/event/message")).put("message_type", "file").put("content", "{\"file_key\":\"test-resource-key\",\"file_name\":\"考勤.csv\"}");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        ((ObjectNode) event.path("header")).put("event_id", "resource-repeat");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        var files = databaseAccess.mapper(ChatMessageResourceMapper.class).selectList(new LambdaQueryWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, enterprise));
        assertEquals(1, files.size());
        assertEquals("pending", files.getFirst().getStatus());
        assertFalse(files.getFirst().getFetchMaterialEncrypted().contains("test-resource-key"));
    }

    @Test
    void unsupportedAndControlEventsAreNotRoutedAsInstructions() {
        enableGroup();
        for (String sender : new String[] {"app", "bot", "unknown"}) {
            String id = ingress.accept(enterprise, bot.getId(), 1L, message("event-" + sender, "message-" + sender, sender, "机器人内部交接提示"));
            var receipt = receipts().selectById(id);
            assertEquals("ignored", receipt.getStatus());
            assertFalse(cipher.decrypt(enterprise, "receipt", id, receipt.getPayloadEncrypted()).toString().contains("交接提示"));
        }
        assertEquals(0, messageCount());
        for (String type : new String[] {"im.message.reaction.created_v1", "im.message.recalled_v1", "unknown.event"}) {
            var event = json.createObjectNode();
            event.putObject("header").put("event_type", type).put("event_id", type);
            event.putObject("event").put("chat_id", externalChat).put("message_id", "message");
            ingress.accept(enterprise, bot.getId(), 1L, event);
        }
        normalizeAll();
        assertEquals(0, messageCount());
        var card = message("card", "card", "user", "卡片文字不能作为指令");
        ((ObjectNode) card.at("/event/message")).put("message_type", "interactive");
        ingress.accept(enterprise, bot.getId(), 1L, card);
        normalizeAll();
        var saved = messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise));
        assertFalse(cipher.decrypt(enterprise, "message", saved.getId(), saved.getContentEncrypted()).path("supported").asBoolean());
    }

    @Test
    void outboundIsFixedAndRepeatedEnqueueCreatesOneMessage() {
        var group = enableGroup();
        var first = enqueue(group);
        assertEquals(first.getId(), enqueue(group).getId());
        assertThrows(ApiException.class, () -> outbound.text(enterprise, group.getId(), bot.getId(), null, null,
            "send-test", "probe", "更换正文", null));
        var lease = sending.claim("sender").orElseThrow();
        var prepared = sending.prepare(lease);
        assertEquals(externalChat, prepared.payload().path("receiveId").asText());
        assertEquals(first.getRequestId(), prepared.payload().path("requestId").asText());
        sending.begin(lease);
        var receipt = new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "feishu", "sent-message",
            null, null, null, 200, "trace-test"), "thread-test");
        sending.complete(lease, receipt);
        sending.complete(lease, receipt);
        var sent = outboundRows().selectById(first.getId());
        assertEquals("sent", sent.getStatus());
        assertEquals(1, sent.getAttemptCount());
        assertEquals("sent-message", sent.getPlatformMessageId());
        assertEquals("thread-test", sent.getPlatformThreadId());
        assertEquals(1, databaseAccess.mapper(ChatOutboundAttemptMapper.class).selectCount(new LambdaQueryWrapper<ChatOutboundAttemptRow>()
            .eq(ChatOutboundAttemptRow::getOutboundId, first.getId())));
        assertTrue(sending.claim("second").isEmpty());
        var app = bots.application(bot);
        var other = new IntegrationApplication("another-application", enterprise, "feishu", app.externalTenantId(), "another-id", 1L);
        var limits = List.of(new ChannelRateLimit("shared_group", false, 60, 1, false, 0));
        assertEquals(Duration.ZERO, rates.acquireConversation(app, "tenant-scope", externalChat, limits));
        assertTrue(rates.acquireConversation(other, "tenant-scope", externalChat, limits).toMillis() > 0);
        assertEquals(Duration.ZERO, rates.acquireConversation(other, "tenant-scope", externalChat + "-different", limits));
    }

    @Test
    void uncertainSendReusesUuidAndRenderedContentWithinPlatformWindow() {
        var group = enableGroup();
        var first = enqueue(group);
        var old = sending.claim("old").orElseThrow();
        var original = sending.prepare(old).payload();
        sending.begin(old);
        sending.complete(old, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.UNKNOWN, "feishu", null,
            "CHAT_SEND_UNKNOWN", "等待核实", null, null, null), null));
        assertEquals("retry_wait", outboundRows().selectById(first.getId()).getStatus());
        outboundRows().update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, first.getId())
            .set(ChatOutboundMessageRow::getNextAttemptAt, Instant.now().minusSeconds(1)));
        var next = sending.claim("next").orElseThrow();
        assertEquals(original, sending.prepare(next).payload());
        assertThrows(ApiException.class, () -> sending.begin(old));
        sending.complete(old, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "feishu", "late-old-response",
            null, null, null, 200, null), null));
        assertEquals("leased", outboundRows().selectById(first.getId()).getStatus());
        assertNull(outboundRows().selectById(first.getId()).getPlatformMessageId());
        sending.begin(next);
        sending.complete(next, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "feishu", "already-sent",
            null, null, null, 200, null), null));
        assertEquals(2, outboundRows().selectById(first.getId()).getAttemptCount());
        assertEquals(first.getRequestId(), outboundRows().selectById(first.getId()).getRequestId());
    }

    @Test
    void expiredUncertainSendIsNotRetriedAfterProcessCrash() {
        var group = enableGroup();
        var first = enqueue(group);
        var lease = sending.claim("crashed").orElseThrow();
        sending.begin(lease);
        outboundRows().update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, first.getId())
            .set(ChatOutboundMessageRow::getFirstSubmittedAt, Instant.now().minusSeconds(3700))
            .set(ChatOutboundMessageRow::getLeaseUntil, Instant.now().minusSeconds(1)));
        assertTrue(sending.claim("recover").isEmpty());
        assertEquals("unknown", outboundRows().selectById(first.getId()).getStatus());
        assertEquals(1, outboundRows().selectById(first.getId()).getAttemptCount());
    }

    @Test
    void leavingGroupBetweenPreparationAndSendingPreventsNetworkAttempt() {
        var group = enableGroup();
        var first = enqueue(group);
        var lease = sending.claim("sender").orElseThrow();
        sending.prepare(lease);
        conversations.left(enterprise, group.getId(), bot.getId(), false);
        assertThrows(ApiException.class, () -> sending.begin(lease));
        sending.defer(lease, Duration.ZERO, "CHAT_SEND_FORBIDDEN", true);
        assertEquals("cancelled", outboundRows().selectById(first.getId()).getStatus());
        assertEquals(0, outboundRows().selectById(first.getId()).getAttemptCount());
    }

    @Test
    void oldRetryingProgressCannotPermanentlyBlockTheFinalMessage() {
        var group = enableGroup();
        var progress = outbound.text(enterprise, group.getId(), bot.getId(), null, null, "old-progress", "assigned", "测试进展", null);
        var complete = outbound.text(enterprise, group.getId(), bot.getId(), null, null, "final-result", "completed", "测试完成", null);
        outboundRows().update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, progress.getId())
            .set(ChatOutboundMessageRow::getCreatedAt, Instant.now().minusSeconds(35)).set(ChatOutboundMessageRow::getStatus, "retry_wait")
            .set(ChatOutboundMessageRow::getNextAttemptAt, Instant.now().plusSeconds(100)));
        outboundRows().update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, complete.getId())
            .set(ChatOutboundMessageRow::getDependsOnOutboundId, progress.getId()));
        var lease = sending.claim("final-sender").orElseThrow();
        assertEquals(complete.getId(), lease.outboundId());
        assertEquals("cancelled", outboundRows().selectById(progress.getId()).getStatus());
        sending.begin(lease);
        sending.complete(lease, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "feishu", "final-sent",
            null, null, null, 200, null), null));
        assertEquals("sent", outboundRows().selectById(complete.getId()).getStatus());
    }

    @Test
    void knownFailuresRetryOnlyTheSendWithTheSameRequestUntilTheAttemptLimit() {
        var group = enableGroup();
        for (var outcome : List.of(Outcome.RETRYABLE_FAILURE, Outcome.TOKEN_EXPIRED)) {
            var queued = outbound.text(enterprise, group.getId(), bot.getId(), null, null,
                "known-failure-" + outcome, "probe", "群策能力验证：固定发送内容", null);
            for (int attempt = 1; attempt <= 5; attempt++) {
                var lease = sending.claim("known-send-failure").orElseThrow();
                assertEquals(queued.getId(), lease.outboundId());
                sending.begin(lease);
                sending.complete(lease, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(outcome, "feishu", null,
                    "SIMULATED_PLATFORM_FAILURE", "明确拒绝本次发送", 9L, 429, "test-trace"), null));
                var saved = outboundRows().selectById(queued.getId());
                assertEquals(attempt == 5 ? "failed" : "retry_wait", saved.getStatus());
                assertEquals(attempt, saved.getAttemptCount());
                assertEquals(queued.getRequestId(), saved.getRequestId());
                assertNull(saved.getFirstSubmittedAt());
                if (attempt < 5) {
                    assertTrue(saved.getNextAttemptAt().isAfter(Instant.now().plusSeconds(7)));
                    outboundRows().update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, queued.getId())
                        .set(ChatOutboundMessageRow::getNextAttemptAt, Instant.now().minusSeconds(1)));
                }
            }
        }
        assertEquals(0, count("agent_run"));
    }

    private ChatOutboundMessageRow enqueue(ChatConversationRow group) {
        return outbound.text(enterprise, group.getId(), bot.getId(), null, null, "send-test", "probe", "群策能力验证：固定发送内容", null);
    }

    private ChatOutboundMessageMapper outboundRows() {
        return databaseAccess.mapper(ChatOutboundMessageMapper.class);
    }

}
