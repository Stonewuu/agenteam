package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.request.ChatConversationUpdateRequest;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 不调用刷新群列表或管理保存接口的新群，直接从平台事件进入正式接收和路由事务。 */
class ChatGroupActivationTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @AfterEach
    void stopPendingDeliveries() {
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).in(ChatOutboundMessageRow::getStatus, "pending", "leased", "retry_wait")
            .set(ChatOutboundMessageRow::getStatus, "cancelled"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstMentionActivatesNewOrPreviouslyDiscoveredGroupAndKeepsTheFirstMessage(boolean previouslyDiscovered) {
        externalChat = "oc_new_" + UUID.randomUUID();
        if (previouslyDiscovered) {
            conversations.observe(bot, bots.application(bot), externalChat, null, "group", "verified_message");
            assertEquals("discovered", conversation().getStatus());
        }
        String receipt = ingress.accept(enterprise, bot.getId(), 1L, message("first", "first", "user", "你有哪些能力"));
        assertEquals("received", receipts().selectById(receipt).getStatus());
        assertEquals("enabled", conversation().getStatus());
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals("routed", onlyMessage().getRoutingStatus());
        assertEquals(initiator.id(), onlyTask().getInitiatorUserId());
        assertEquals("full_allowed", conversation().getResultPolicy());
        assertEquals("chat_full", onlyTask().getOutputVisibility());
        assertEquals("你有哪些能力", cipher.decrypt(enterprise, "message", onlyMessage().getId(), onlyMessage().getContentEncrypted()).path("text").asText());
        var member = conversations.member(enterprise, conversation().getId(), bot.getId());
        assertTrue(member.getReceiveEnabled());
        assertTrue(member.getSendEnabled());
        assertTrue(member.getCollaborationEnabled());
        var lease = sending.claim("first-ack").orElseThrow();
        assertEquals(enterprise, lease.enterpriseId());
        assertEquals("first", sending.prepare(lease).payload().path("replyToMessageId").asText());
    }

    @Test
    void joinEventActivatesBeforeAnyUserMessageAndRepeatingItDoesNotChangeSettings() {
        externalChat = "oc_join_" + UUID.randomUUID();
        join(bot, "join", Instant.now().minusSeconds(2));
        var current = conversation();
        assertEquals("enabled", current.getStatus());
        assertEquals(0, messageCount());
        assertEquals(0, taskCount());
        join(bot, "join", Instant.now().minusSeconds(2));
        join(bot, "join-again", Instant.now().minusSeconds(1));
        assertEquals(current.getRevision(), conversation().getRevision());
        assertEquals(1, conversations.members(enterprise, current.getId()).size());
        routing.route(enterprise, receive("after-join", "你好").getId());
        assertEquals(1, taskCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "archived"})
    void explicitStopIsNeverReversedByJoinOrMention(String status) {
        externalChat = "oc_stopped_" + UUID.randomUUID();
        join(bot, "joined", Instant.now().minusSeconds(2));
        var current = conversation();
        var view = conversations.update(users.findById(admin).orElseThrow(), enterprise, false, current.getId(),
            new ChatConversationUpdateRequest(current.getRevision(), status, List.of(bot.getId()), "single_bot",
                bot.getId(), bot.getId(), "mention", "summary_link", Map.of()));
        assertFalse(view.limits().containsKey("automaticBotSetup"));
        join(bot, "joined-again", Instant.now().minusSeconds(1));
        String receipt = ingress.accept(enterprise, bot.getId(), 1L, message("stopped-message", "stopped-message", "user", "不能恢复的正文"));
        normalizeAll();
        assertEquals(status, conversation().getStatus());
        assertEquals("ignored", receipts().selectById(receipt).getStatus());
        assertFalse(cipher.decrypt(enterprise, "receipt", receipt, receipts().selectById(receipt).getPayloadEncrypted()).toString().contains("不能恢复的正文"));
        assertEquals(0, taskCount());
    }

    @Test
    void manuallyExcludedRoleStaysExcludedWhenItReceivesAMention() {
        var peer = secondBot();
        externalChat = "oc_selected_" + UUID.randomUUID();
        join(bot, "join-first", Instant.now().minusSeconds(3));
        join(peer, "join-peer", Instant.now().minusSeconds(2));
        var current = conversation();
        var view = conversations.update(users.findById(admin).orElseThrow(), enterprise, false, current.getId(),
            new ChatConversationUpdateRequest(current.getRevision(), "enabled", List.of(bot.getId()), "single_bot",
                bot.getId(), bot.getId(), "mention", "summary_link", Map.of()));
        assertFalse(view.limits().containsKey("automaticBotSetup"));
        ingress.accept(enterprise, peer.getId(), 1L, forBot(peer, "excluded", "excluded", "不能恢复被排除岗位"));
        normalizeAll();
        var member = conversations.member(enterprise, current.getId(), peer.getId());
        assertFalse(member.getReceiveEnabled());
        assertFalse(member.getSendEnabled());
        assertFalse(member.getCollaborationEnabled());
        assertEquals(0, messageCount());
        assertEquals(0, taskCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void delayedEventsCannotUndoLeaveAndConfirmedRejoinRestoresAutomaticGroup(boolean withJoinEvent) {
        externalChat = "oc_rejoin_" + UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(20);
        join(bot, "join-before-leave", start);
        var current = conversation();
        conversations.left(enterprise, current.getId(), bot.getId(), false, start.plusSeconds(5));
        join(bot, "old-join", start.plusSeconds(1));
        var late = message("late", "late", "user", "过期的消息");
        late.withObject("/event/message").put("create_time", Long.toString(start.plusSeconds(3).toEpochMilli()));
        ingress.accept(enterprise, bot.getId(), 1L, late);
        normalizeAll();
        assertEquals("left", conversations.member(enterprise, current.getId(), bot.getId()).getMembershipStatus());
        assertEquals(0, messageCount());
        if (withJoinEvent) {
            join(bot, "new-join", start.plusSeconds(10));
        }
        routing.route(enterprise, receive("after-rejoin", "继续处理").getId());
        assertTrue(conversations.member(enterprise, current.getId(), bot.getId()).getReceiveEnabled());
        assertEquals(1, taskCount());
    }

    @Test
    void verifiedBotsJoinFromTheirFirstMessagesAndShareOneTaskEvenAcrossModeChange() {
        var peer = secondBot();
        verified(bot);
        verified(peer);
        externalChat = "oc_multiple_" + UUID.randomUUID();
        var first = both(peer, "first-app", "same-message");
        ingress.accept(enterprise, bot.getId(), 1L, first);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals(0, taskCount(), "另一被明确提及的岗位尚未收到消息时先等待");
        ingress.accept(enterprise, peer.getId(), 1L, both(peer, "second-app", "same-message"));
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals("multi_bot", conversation().getIngressMode());
        assertEquals(1, messageCount());
        assertEquals(1, taskCount());
        assertEquals(2, databaseAccess.mapper(CollaborationParticipantMapper.class).selectCount(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getTaskId, onlyTask().getId())));
        ingress.accept(enterprise, peer.getId(), 1L, both(peer, "second-replay", "same-message"));
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals(1, messageCount());
        assertEquals(1, taskCount());
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, bot.getConnectionId())
            .setIncrBy(EnterpriseIntegrationRow::getCredentialRevision, 1));
        ingress.accept(enterprise, bot.getId(), 1L, both(peer, "credential-replay", "same-message"));
        normalizeAll();
        assertEquals("multi_bot", conversation().getIngressMode());
        assertEquals(1, messageCount());
        assertEquals(1, taskCount());
    }

    @Test
    void unverifiedSecondEntryReceivesUsefulGuidanceInsteadOfSilence() {
        var peer = secondBot();
        externalChat = "oc_guidance_" + UUID.randomUUID();
        join(bot, "first-joined", Instant.now().minusSeconds(3));
        join(peer, "second-joined", Instant.now().minusSeconds(2));
        var event = forBot(peer, "secondary", "secondary", "你有哪些能力");
        ingress.accept(enterprise, peer.getId(), 1L, event);
        normalizeAll();
        ingress.accept(enterprise, peer.getId(), 1L, event);
        normalizeAll();
        assertEquals(0, taskCount());
        var notices = databaseAccess.mapper(ChatOutboundMessageMapper.class).selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getKind, "guidance"));
        assertEquals(1, notices.size());
        var notice = notices.getFirst();
        assertTrue(cipher.decrypt(enterprise, "outbound", notice.getId(), notice.getPayloadEncrypted()).at("/content/text").asText().contains("人事"));
    }

    @Test
    void automaticActivationDoesNotBypassAccountBinding() {
        externalChat = "oc_identity_" + UUID.randomUUID();
        var event = message("unbound", "unbound", "user", "你好");
        event.withObject("/event/sender/sender_id").put("open_id", "ou_unbound_user");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals("enabled", conversation().getStatus());
        assertEquals("awaiting_binding", onlyMessage().getRoutingStatus());
        assertEquals(0, taskCount());
    }

    private void join(ChatBotBindingRow receiver, String id, Instant at) {
        var event = json.createObjectNode();
        event.putObject("header").put("event_id", id).put("event_type", "im.chat.member.bot.added_v1")
            .put("create_time", Long.toString(at.toEpochMilli()));
        event.putObject("event").put("chat_id", externalChat);
        ingress.accept(enterprise, receiver.getId(), 1L, event);
        normalizeAll();
    }

    private ObjectNode forBot(ChatBotBindingRow receiver, String delivery, String id, String text) {
        var event = message(delivery, id, "user", text);
        event.withObject("/event/message").putArray("mentions").addObject().put("name", receiver.getRoleName())
            .putObject("id").put("open_id", receiver.getPlatformBotId());
        return event;
    }

    private ObjectNode both(ChatBotBindingRow peer, String delivery, String id) {
        var event = message(delivery, id, "user", "请人事和财务一起核对");
        event.withObject("/event/message").withArray("mentions").addObject().put("name", peer.getRoleName())
            .putObject("id").put("open_id", peer.getPlatformBotId());
        return event;
    }

    private void verified(ChatBotBindingRow selected) {
        var value = json.createObjectNode();
        value.putObject("crossBotIdentity").put("status", "passed").put("credentialRevision", bots.application(selected).credentialRevision());
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getEnterpriseId, enterprise).eq(ChatBotBindingRow::getId, selected.getId())
            .set(ChatBotBindingRow::getCapabilitiesJson, value.toString()));
    }

    @Test
    void pausingGroupRetainsRolesAndPolicyForResuming() {
        var input = new ChatConversationUpdateRequest(1L, "disabled", List.of(bot.getId()), "single_bot", bot.getId(), bot.getId(), "mention",
            "summary_link", Map.of("maxRuns", 40));
        var paused = conversations.update(users.findById(admin).orElseThrow(), enterprise, false, group.getId(), input);
        assertEquals("disabled", paused.status());
        var member = conversations.member(enterprise, group.getId(), bot.getId());
        assertTrue(member.getCollaborationEnabled());
        assertFalse(member.getReceiveEnabled());
        assertFalse(member.getSendEnabled());
        var resumed = conversations.update(users.findById(admin).orElseThrow(), enterprise, false, group.getId(),
            new ChatConversationUpdateRequest(paused.revision(), "enabled", List.of(bot.getId()), "single_bot", bot.getId(), bot.getId(), "mention",
                "summary_link", Map.of("maxRuns", 40)));
        assertEquals("enabled", resumed.status());
    }
}
