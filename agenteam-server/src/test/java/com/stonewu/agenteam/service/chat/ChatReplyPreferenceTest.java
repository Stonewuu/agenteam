package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatSendLease;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.request.CollaborationReplyModeRequest;
import com.stonewu.agenteam.service.collaboration.CollaborationReplyModeService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 隔离数据库验证成员设置和实际发送之前的策略边界，不调用真实平台。 */
class ChatReplyPreferenceTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private ChatReplyPreferenceService preferences;
    @Autowired private CollaborationReplyModeService modes;
    @Autowired private ChatIdentityService identities;

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
    void ordinaryInitiatorChangesOnlyReplySettingsWithoutStartingWork() {
        var task = start();
        var saved = modes.save(actor(), task.getId(), new CollaborationReplyModeRequest("summary_link", 0L, true, 0L));
        assertEquals("summary_link", saved.outputVisibility());
        assertEquals(1, saved.revision());
        assertEquals("summary_link", saved.conversation().outputVisibility());
        var after = onlyTask();
        assertEquals(task.getRevision(), after.getRevision());
        assertEquals(task.getInputRevision(), after.getInputRevision());
        assertEquals(task.getObjectiveEncrypted(), after.getObjectiveEncrypted());
        assertEquals(task.getStatus(), after.getStatus());
        assertEquals(task.getRunCount(), after.getRunCount());
        assertEquals(task.getWorkCount(), after.getWorkCount());
        assertTrue(after.getReplyModeExplicit());
        assertEquals(1, preferences.list(actor(), null, 10).items().size());
    }

    @Test
    void otherMembersIncludingAdministratorsCannotChangeOrListPrivatePreferences() {
        var task = start();
        var other = identities.actor(enterprise, admin);
        assertThrows(ApiException.class, () -> modes.get(other, task.getId()));
        assertThrows(ApiException.class, () -> modes.save(other, task.getId(), new CollaborationReplyModeRequest("summary_link", 0L, false, null)));
        assertThrows(ApiException.class, () -> preferences.get(other, task.getConversationId()));
        assertThrows(ApiException.class, () -> preferences.save(other, task.getConversationId(), "summary_link", 0L));
        assertTrue(preferences.list(other, null, 10).items().isEmpty());
        assertEquals("chat_full", onlyTask().getOutputVisibility());
    }

    @Test
    void personalDefaultAppliesOnlyToNewTasksAndSameConversationAcrossBots() {
        var first = start();
        preferences.save(actor(), first.getConversationId(), "summary_link", 0L);
        secondBot();
        assertEquals("summary_link", preferences.initial(enterprise, initiator.id(), conversation()).visibility());
        assertEquals("chat_full", onlyTask().getOutputVisibility());
        var next = receive(UUID.randomUUID().toString(), "新任务：检查下月计划");
        routing.route(enterprise, next.getId());
        var created = databaseAccess.mapper(CollaborationTaskMapper.class).selectOne(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getOriginMessageId, next.getId()));
        assertEquals("summary_link", created.getOutputVisibility());
        assertTrue(created.getReplyModeExplicit());
        externalChat = "oc_" + UUID.randomUUID();
        var otherGroup = enableGroup();
        assertEquals("chat_full", preferences.initial(enterprise, initiator.id(), otherGroup).visibility());
        assertEquals("chat_full", preferences.initial(enterprise, admin, group).visibility());
    }

    @Test
    void restrictionIsVisibleAndBlocksFullRepliesEvenForPreviouslyFullTasks() {
        var task = start();
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, task.getConversationId())
            .set(ChatConversationRow::getResultPolicy, "summary_link"));
        assertFalse(modes.get(actor(), task.getId()).conversation().fullAllowed());
        assertEquals("summary_link", modes.get(actor(), task.getId()).outputVisibility());
        assertThrows(ApiException.class, () -> modes.save(actor(), task.getId(), new CollaborationReplyModeRequest("chat_full", 0L, false, null)));
        var row = outbound.text(enterprise, task.getConversationId(), bot.getId(), task.getId(), null,
            "restricted-question", "waiting_input", "详细问题不应进入群聊", null);
        String actual = sending.prepare(lease(row)).payload().at("/content/body/elements/0/content").asText();
        assertTrue(actual.contains("请打开任务"));
        assertFalse(actual.contains("详细问题"));
    }

    @Test
    void modeChangedAfterPreparationMustReprepareBeforeNetworkSubmission() {
        var task = start();
        var row = outbound.text(enterprise, task.getConversationId(), bot.getId(), task.getId(), null,
            "pending-answer", "completed", "原始完整答案", null);
        var lease = lease(row);
        var prepared = sending.prepare(lease);
        modes.save(actor(), task.getId(), new CollaborationReplyModeRequest("summary_link", 0L, false, null));
        assertEquals("CHAT_REPLY_MODE_CHANGED", assertThrows(ApiException.class, () -> sending.begin(lease, prepared.payload())).code());
        var current = sending.prepare(lease);
        assertTrue(current.payload().at("/content/body/elements/0/content").asText().contains("请打开任务"));
        assertFalse(current.payload().toString().contains("原始完整答案"));
        sending.begin(lease, current.payload());
        var stored = databaseAccess.mapper(ChatOutboundMessageMapper.class).selectById(row.getId());
        assertFalse(cipher.decrypt(enterprise, "outbound-rendered", row.getId(), stored.getRenderedPayloadEncrypted()).toString().contains("原始完整答案"));
    }

    @Test
    void alreadySubmittedContentIsNeverRewrittenUnderSamePlatformRequest() {
        var task = start();
        var row = outbound.text(enterprise, task.getConversationId(), bot.getId(), task.getId(), null,
            "submitted-answer", "completed", "已经提交平台的答案", null);
        var lease = lease(row);
        sending.begin(lease, sending.prepare(lease).payload());
        var frozen = databaseAccess.mapper(ChatOutboundMessageMapper.class).selectById(row.getId()).getRenderedPayloadEncrypted();
        modes.save(actor(), task.getId(), new CollaborationReplyModeRequest("summary_link", 0L, false, null));
        assertEquals("CHAT_SEND_UNKNOWN", assertThrows(ApiException.class, () -> sending.prepare(lease)).code());
        assertEquals(frozen, databaseAccess.mapper(ChatOutboundMessageMapper.class).selectById(row.getId()).getRenderedPayloadEncrypted());
    }

    @Test
    void privatePreferenceIdentifiesItsBotWithoutShowingAnotherConversation() {
        externalChat = "oc_private_" + UUID.randomUUID();
        var incoming = message("private", "private", "user", "你好");
        incoming.withObject("/event/message").put("chat_type", "p2p").remove("mentions");
        ingress.accept(enterprise, bot.getId(), 1L, incoming);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        var preference = preferences.get(actor(), onlyTask().getConversationId());
        assertEquals("direct", preference.kind());
        assertEquals(List.of(bot.getRoleName()), preference.botNames());
        assertEquals(1, preferences.list(actor(), null, 10).items().size());
        assertEquals(preference.botNames(), preferences.list(actor(), null, 10).items().getFirst().botNames());
    }

    @Test
    void staleTaskOrDefaultVersionCannotPartiallySaveSettings() {
        var task = start();
        preferences.save(actor(), task.getConversationId(), "chat_full", 0L);
        assertThrows(ApiException.class, () -> modes.save(actor(), task.getId(), new CollaborationReplyModeRequest("summary_link", 0L, true, 0L)));
        assertEquals("chat_full", onlyTask().getOutputVisibility());
        assertEquals(0, modes.get(actor(), task.getId()).revision());
        modes.save(actor(), task.getId(), new CollaborationReplyModeRequest("summary_link", 0L, false, null));
        assertThrows(ApiException.class, () -> modes.save(actor(), task.getId(), new CollaborationReplyModeRequest("chat_full", 0L, false, null)));
        assertEquals("summary_link", onlyTask().getOutputVisibility());
    }

    private CollaborationTaskRow start() {
        var message = receive(UUID.randomUUID().toString(), "整理下个月的计划");
        routing.route(enterprise, message.getId());
        return onlyTask();
    }

    private AuthContext actor() {
        return identities.actor(enterprise, initiator.id(), binding.getId());
    }

    private ChatSendLease lease(ChatOutboundMessageRow row) {
        assertEquals(1, databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getId, row.getId())
            .eq(ChatOutboundMessageRow::getStatus, "pending").set(ChatOutboundMessageRow::getStatus, "leased")
            .set(ChatOutboundMessageRow::getLeaseOwner, "reply-test").set(ChatOutboundMessageRow::getLeaseVersion, 1L)
            .set(ChatOutboundMessageRow::getLeaseUntil, Instant.now().plusSeconds(60))));
        return new ChatSendLease(enterprise, row.getId(), 1L, "reply-test");
    }
}
