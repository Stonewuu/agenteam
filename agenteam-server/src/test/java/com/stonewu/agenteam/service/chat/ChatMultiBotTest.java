package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationBotMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Mention;
import com.stonewu.agenteam.model.chat.request.ChatCheckRequest;
import com.stonewu.agenteam.model.chat.request.ChatConversationUpdateRequest;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 两个真实应用范围的合成事件分别进入接收事务，不把另一应用的账号当成入口账号。 */
class ChatMultiBotTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired ChatCapabilityService capabilities;
    @Autowired ChatMentionIdentityService mentionIdentities;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);

        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void unselectedRoleKeepsItsNameAndCanBeSelectedAgain() {
        var finance = secondBot();
        var actor = users.findById(admin).orElseThrow();
        var current = conversations.require(enterprise, group.getId());
        var saved = conversations.update(actor, enterprise, false, group.getId(), new ChatConversationUpdateRequest(
            current.getRevision(), "enabled", List.of(bot.getId()), "single_bot", bot.getId(), bot.getId(), "mention", "summary_link", Map.of()));
        var unselected = saved.bots().stream().filter(value -> value.botId().equals(finance.getId())).findFirst().orElseThrow();
        assertEquals("财务", unselected.roleName());
        assertFalse(unselected.collaborationEnabled());
        assertEquals("present", unselected.membershipStatus());
        var restored = conversations.update(actor, enterprise, false, group.getId(), new ChatConversationUpdateRequest(
            saved.revision(), "enabled", List.of(bot.getId(), finance.getId()), "single_bot", bot.getId(), bot.getId(), "mention", "summary_link", Map.of()));
        assertTrue(restored.bots().stream().filter(value -> value.botId().equals(finance.getId())).findFirst().orElseThrow().collaborationEnabled());
        assertEquals("enabled", bots.require(enterprise, finance.getId()).getStatus());
    }

    @Test
    void appScopedBotAndHumanMentionsMergeIntoOneTaskWithoutUsingNames() {
        var peer = peer();
        verifyScopedPair(peer);
        enableMultiple();
        ingress.accept(enterprise, peer.getId(), 1L, scopedBoth(peer, "scoped-peer", "scoped-business", "核对考勤", true));
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals(0, taskCount());
        ingress.accept(enterprise, bot.getId(), 1L, scopedBoth(peer, "scoped-entry", "scoped-business", "核对考勤", false));
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        var task = onlyTask();
        assertEquals(bot.getId(), task.getEntryBotId());
        assertEquals(1, messageCount());
        assertEquals("routed", onlyMessage().getRoutingStatus());
        ingress.accept(enterprise, peer.getId(), 1L, scopedBoth(peer, "scoped-replay", "scoped-business", "核对考勤", true));
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals(task.getId(), onlyTask().getId());
    }

    @Test
    void mentioningOnlyPeerCreatesPeerOwnedTaskWhicheverApplicationArrivesFirst() {
        var peer = peer();
        verifyScopedPair(peer);
        enableMultiple();
        var first = scopedBoth(peer, "only-peer-entry", "only-peer", "请财务处理", false);
        var second = scopedBoth(peer, "only-peer-receiver", "only-peer", "请财务处理", true);
        for (var event : new ObjectNode[]{first, second}) {
            var values = (ArrayNode) event.at("/event/message/mentions");
            var selected = values.get(1).deepCopy();
            values.removeAll().add(selected);
            event.withObject("/event/message").put("content", json.createObjectNode().put("text", "@_user_2 请财务处理").toString());
        }
        ingress.accept(enterprise, bot.getId(), 1L, first);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals("pending", onlyMessage().getRoutingStatus());
        ingress.accept(enterprise, peer.getId(), 1L, second);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals(peer.getId(), onlyTask().getEntryBotId());
        assertEquals(initiator.id(), onlyTask().getInitiatorUserId());
        assertEquals(1, messageCount());
    }

    @Test
    void mismatchedMentionPositionsCannotEstablishCrossApplicationAliases() throws Exception {
        var peer = peer();
        String marker = marker(peer);
        var first = scopedBoth(peer, "position-entry", "position", marker, false);
        var second = scopedBoth(peer, "position-peer", "position", marker, true);
        ((ObjectNode) second.at("/event/message/mentions/0")).put("key", "@_user_2");
        ((ObjectNode) second.at("/event/message/mentions/1")).put("key", "@_user_1");
        ingress.accept(enterprise, bot.getId(), 1L, first);
        ingress.accept(enterprise, peer.getId(), 1L, second);
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "crossBotIdentity"));
        assertFalse(json.readTree(bots.require(enterprise, bot.getId()).getConfigJson()).has("verifiedBotMentions"));
    }

    @Test
    void changedPeerCredentialsInvalidatePreviouslyVerifiedMentionAlias() {
        var peer = peer();
        verifyScopedPair(peer);
        var mentions = List.of(new Mention(
            "ou_finance_in_hr", "同名机器人", "bot", "@_user_1"));
        var receiver = bots.require(enterprise, bot.getId());
        var second = bots.require(enterprise, peer.getId());
        assertTrue(mentionIdentities.supportsGroup(List.of(receiver, second)));
        var third = additionalBot("ou_third", "其他岗位");
        assertFalse(mentionIdentities.supportsGroup(List.of(receiver, second, third)));
        assertEquals(peer.getPlatformBotId(), mentionIdentities.resolve(receiver, mentions).getFirst().externalId());
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, peer.getConnectionId())
            .setIncrBy(EnterpriseIntegrationRow::getCredentialRevision, 1));
        assertEquals("mention:@_user_1", mentionIdentities.resolve(receiver, mentions).getFirst().externalId());
        assertFalse(mentionIdentities.supportsGroup(List.of(receiver, bots.require(enterprise, peer.getId()))));
    }

    private void verifyScopedPair(ChatBotBindingRow peer) {
        String marker = marker(peer);
        ingress.accept(enterprise, peer.getId(), 1L, scopedBoth(peer, "joint-scoped-peer", "joint-scoped", marker, true));
        ingress.accept(enterprise, bot.getId(), 1L, scopedBoth(peer, "joint-scoped-entry", "joint-scoped", marker, false));
        assertTrue(bots.capability(bots.require(enterprise, bot.getId()), "crossBotIdentity"));
        assertTrue(bots.capability(bots.require(enterprise, peer.getId()), "crossBotIdentity"));
        assertEquals(0, messageCount());
    }

    private ObjectNode scopedBoth(ChatBotBindingRow peer, String eventId, String messageId, String text, boolean peerSender) {
        var event = both(peer, eventId, messageId, "@_user_1 @_user_2 @_user_3 " + text, peerSender);
        var mentions = (ArrayNode) event.at("/event/message/mentions");
        ((ObjectNode) mentions.get(0)).put("key", "@_user_1").put("name", "同名机器人").withObject("/id")
            .put("open_id", peerSender ? "ou_hr_in_finance" : bot.getPlatformBotId());
        ((ObjectNode) mentions.get(1)).put("key", "@_user_2").put("name", "同名机器人").withObject("/id")
            .put("open_id", peerSender ? peer.getPlatformBotId() : "ou_finance_in_hr");
        mentions.addObject().put("key", "@_user_3").put("name", "同名机器人").putObject("id")
            .put("open_id", peerSender ? "ou_colleague_in_finance" : "ou_colleague_in_hr");
        return event;
    }

    @Test
    void differentMessageIdsFailJointProofAndStartingAnotherProbeClearsOldProof() throws Exception {
        var peer = peer();
        verifyPair(peer);
        assertTrue(bots.capability(bots.require(enterprise, bot.getId()), "crossBotIdentity"));
        assertTrue(bots.capability(bots.require(enterprise, peer.getId()), "crossBotIdentity"));
        assertEquals(0, messageCount());
        assertEquals(0, taskCount());
        String marker = marker(peer);
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "crossBotIdentity"));
        ingress.accept(enterprise, bot.getId(), 1L, both(peer, "joint-a", "message-a", marker, false));
        ingress.accept(enterprise, peer.getId(), 1L, both(peer, "joint-b", "message-b", marker, true));
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "crossBotIdentity"));
        assertEquals("failed", json.readTree(bots.require(enterprise, peer.getId()).getCapabilitiesJson()).path("crossBotIdentity").path("status").asText());
    }

    @Test
    void peerArrivesFirstButOnlyDesignatedEntryIdentityCreatesTheTask() {
        var peer = peer();
        verifyPair(peer);
        enableMultiple();
        ingress.accept(enterprise, peer.getId(), 1L, both(peer, "peer-first", "shared", "统计工资与考勤", true));
        normalizeAll();
        var saved = onlyMessage();
        routing.route(enterprise, saved.getId());
        assertEquals(0, taskCount());
        assertEquals("pending", messages().selectById(saved.getId()).getRoutingStatus());
        ingress.accept(enterprise, bot.getId(), 1L, both(peer, "entry-second", "shared", "统计工资与考勤", false));
        normalizeAll();
        routing.route(enterprise, saved.getId());
        assertEquals(1, messageCount());
        var task = onlyTask();
        assertEquals(bot.getId(), task.getEntryBotId());
        assertEquals(binding.getId(), task.getOriginUserBindingId());
        assertEquals(initiator.id(), task.getInitiatorUserId());
        ingress.accept(enterprise, peer.getId(), 1L, both(peer, "late-duplicate", "shared", "统计工资与考勤", true));
        normalizeAll();
        routing.route(enterprise, saved.getId());
        assertEquals(task.getId(), onlyTask().getId());
    }

    @Test
    void bindingInAnotherApplicationCannotReplaceTheMissingEntryBinding() {
        var peer = peer();
        verifyPair(peer);
        enableMultiple();
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getChatEnabled, false));
        ingress.accept(enterprise, peer.getId(), 1L, both(peer, "bound-peer", "unbound-entry", "需要人事处理", true));
        ingress.accept(enterprise, bot.getId(), 1L, both(peer, "unbound-main", "unbound-entry", "需要人事处理", false));
        normalizeAll();
        var saved = onlyMessage();
        routing.route(enterprise, saved.getId());
        assertEquals("awaiting_binding", messages().selectById(saved.getId()).getRoutingStatus());
        assertEquals(0, taskCount());
    }

    @Test
    void differentContentsForTheSameMessageNeverStartASecondInterpretation() {
        var peer = peer();
        verifyPair(peer);
        enableMultiple();
        ingress.accept(enterprise, peer.getId(), 1L, both(peer, "content-peer", "content-conflict", "按一月计算", true));
        ingress.accept(enterprise, bot.getId(), 1L, both(peer, "content-entry", "content-conflict", "按二月计算", false));
        normalizeAll();
        var saved = onlyMessage();
        routing.route(enterprise, saved.getId());
        assertEquals("awaiting_clarification", messages().selectById(saved.getId()).getRoutingStatus());
        assertEquals(0, taskCount());
    }

    @Test
    void rotatingCredentialsInvalidatesJointCapabilityInsteadOfFallingBackToAnotherDedupeScope() {
        var peer = peer();
        verifyPair(peer);
        enableMultiple();
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()).setIncrBy(EnterpriseIntegrationRow::getCredentialRevision, 1));
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "crossBotIdentity"));
        ingress.accept(enterprise, bot.getId(), 1L, both(peer, "rotated", "rotated", "不能改用机器人范围建立另一份任务", false));
        normalizeAll();
        assertEquals(0, messageCount());
        assertEquals(0, taskCount());
    }

    @Test
    void singleEntryDoesNotTreatMentioningOnlyAnotherRoleAsANewTask() {
        var peer = peer();
        var event = message("single-other", "single-other", "user", "请财务统计");
        ((ArrayNode) event.at("/event/message/mentions")).removeAll().addObject().put("name", "财务")
            .putObject("id").put("open_id", peer.getPlatformBotId());
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals("ignored", onlyMessage().getRoutingStatus());
        assertEquals(0, taskCount());
        assertFalse(cipher.decrypt(enterprise, "message", onlyMessage().getId(), onlyMessage().getContentEncrypted()).toString().contains("请财务统计"));
    }

    private ChatBotBindingRow peer() {
        var peer = secondBot();
        var account = new UserChannelBindingRow();
        account.setId(UUID.randomUUID().toString());
        account.setEnterpriseId(enterprise);
        account.setConnectionId(peer.getConnectionId());
        account.setUserId(initiator.id());
        account.setExternalSubjectType("feishu_open_id");
        account.setExternalSubjectId("ou_peer_user");
        account.setChatEnabled(true);
        account.setAuthorizedAt(Instant.now());
        databaseAccess.mapper(UserChannelBindingMapper.class).insert(account);
        return peer;
    }

    private void enableMultiple() {
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getId, group.getId()).set(ChatConversationRow::getIngressMode, "multi_bot"));
        databaseAccess.mapper(ChatConversationBotMapper.class).update(new LambdaUpdateWrapper<ChatConversationBotRow>()
            .eq(ChatConversationBotRow::getConversationId, group.getId()).set(ChatConversationBotRow::getReceiveEnabled, true));
    }

    private String marker(ChatBotBindingRow peer) {
        return capabilities.receive(initiator, enterprise, bot.getId(), new ChatCheckRequest(1L, group.getId(), "text", null, peer.getId())).get("marker").toString();
    }

    private void verifyPair(ChatBotBindingRow peer) {
        String marker = marker(peer);
        String id = UUID.randomUUID().toString();
        ingress.accept(enterprise, peer.getId(), 1L, both(peer, id + "-peer", id, marker, true));
        ingress.accept(enterprise, bot.getId(), 1L, both(peer, id + "-entry", id, marker, false));
    }

    private ObjectNode both(ChatBotBindingRow peer, String eventId, String messageId, String text, boolean peerSender) {
        var event = message(eventId, messageId, "user", text);
        ((ArrayNode) event.at("/event/message/mentions")).addObject().put("name", "财务").putObject("id").put("open_id", peer.getPlatformBotId());
        if (peerSender) {
            event.withObject("/event/sender/sender_id").put("open_id", "ou_peer_user");
        }
        return event;
    }
}
