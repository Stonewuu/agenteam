package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatSendLease;
import com.stonewu.agenteam.model.chat.request.ChatCheckRequest;
import com.stonewu.agenteam.model.collaboration.request.CollaborationReplyModeRequest;
import com.stonewu.agenteam.model.collaboration.request.CollaborationResultResendRequest;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.service.collaboration.CollaborationReplyModeService;
import com.stonewu.agenteam.service.collaboration.CollaborationResultResendService;
import com.stonewu.agenteam.service.http.ApiException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 正式事务与模拟平台回调验证，不向真实群发送消息。 */
class ChatReplyInteractionTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired private CollaborationReplyModeService modes;
    @Autowired private ChatReplyPreferenceService preferences;
    @Autowired private ChatCardInteractionTransactions cards;
    @Autowired private ChatCapabilityService capabilities;
    @Autowired private CollaborationResultResendService resends;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
        registry.add("execution.maintenance-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void onlyOriginalInitiatorCanChangeTaskAndDuplicateCallbackDoesNotRepeatWork() {
        startTask("整理合成计划");
        var task = onlyTask();
        var source = outgoing("accepted").getFirst();
        markSent(source);
        for (String field : List.of("app_id", "open_chat_id", "open_message_id")) {
            var invalidEvent = callback(source, "wrong-" + field, "reply_mode_open");
            invalidEvent.withObject(field.equals("app_id") ? "/header" : "/event/context").put(field, "wrong-value");
            assertThrows(ApiException.class, () -> accept(invalidEvent));
        }
        var invalidEvent = callback(source, "wrong-lease", "reply_mode_open");
        assertThrows(ApiException.class, () -> cards.accept(enterprise, bot.getId(), 999L, bots.application(bot).credentialRevision(), bot.getRevision(), invalidEvent));
        assertTrue(outgoing("reply_settings_update").isEmpty());
        var event = callback(source, "save-one", "reply_mode_save");
        event.withObject("/event/action/value").put("outputVisibility", "summary_link").put("expectedRevision", 0).put("expectedDefaultRevision", 0);
        event.withObject("/event/action/form_value").put("saveDefault", true);
        var saved = accept(event);
        assertEquals("success", saved.at("/toast/type").asText());
        int updates = outgoing("reply_settings_update").size();
        assertEquals(saved, accept(event));
        assertEquals(updates, outgoing("reply_settings_update").size());
        assertEquals("summary_link", modes.get(actor(), task.getId()).outputVisibility());
        assertEquals("summary_link", preferences.get(actor(), group.getId()).outputVisibility());
        assertEquals(task.getInputRevision(), onlyTask().getInputRevision());
        assertEquals(task.getRunCount(), onlyTask().getRunCount());
        assertEquals(task.getWorkCount(), onlyTask().getWorkCount());
        var stranger = callback(source, "someone-else", "reply_mode_open");
        stranger.withObject("/event/operator").put("open_id", "ou_unbound");
        assertEquals("CHANNEL_BINDING_REQUIRED", assertThrows(ApiException.class, () -> accept(stranger)).code());
        var otherBinding = new UserChannelBindingRow();
        otherBinding.setId(UUID.randomUUID().toString());
        otherBinding.setEnterpriseId(enterprise);
        otherBinding.setConnectionId(bot.getConnectionId());
        otherBinding.setUserId(admin);
        otherBinding.setExternalSubjectType("feishu_open_id");
        otherBinding.setExternalSubjectId("ou_other_member");
        otherBinding.setChatEnabled(true);
        otherBinding.setAuthorizedAt(Instant.now());
        databaseAccess.mapper(UserChannelBindingMapper.class).insert(otherBinding);
        stranger.withObject("/event/operator").put("open_id", "ou_other_member");
        assertEquals("COLLABORATION_REPLY_OWNER_REQUIRED", assertThrows(ApiException.class, () -> accept(stranger)).code());
        accept(callback(source, "open-setting", "reply_mode_open"));
        accept(callback(source, "close-setting", "reply_mode_close"));
        var cardUpdates = outgoing("reply_settings_update");
        assertEquals(cardUpdates.get(cardUpdates.size() - 2).getId(), cardUpdates.getLast().getDependsOnOutboundId());
        var stale = event.deepCopy();
        stale.withObject("/header").put("event_id", "stale-setting");
        assertEquals("VERSION_CONFLICT", assertThrows(ApiException.class, () -> accept(stale)).code());
    }

    @Test
    void callbackProbeEnablesControlsOnlyAfterAnAuthenticatedClick() {
        startTask("整理合成计划");
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "cardInteraction"));
        var result = capabilities.send(enterprise, bot.getId(), new ChatCheckRequest(bot.getRevision(), group.getId(), "card", null), "probe-one");
        var source = databaseAccess.mapper(ChatOutboundMessageMapper.class).selectById(result.get("outboundId"));
        markSent(source);
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "cardInteraction"));
        assertEquals("success", accept(callback(source, "probe-click", "reply_mode_probe")).at("/toast/type").asText());
        assertTrue(bots.capability(bots.require(enterprise, bot.getId()), "cardInteraction"));
        var accepted = outgoing("accepted").getFirst();
        var prepared = sending.prepare(lease(accepted)).payload();
        assertEquals("callback", prepared.at("/content/body/elements/3/behaviors/0/type").asText());
    }

    @Test
    void settingCommandsDoNotCreateNewTasksOrInputsAndRestrictionHasClearFeedback() {
        var command = receive("settings", "回复方式");
        routing.route(enterprise, command.getId());
        routing.route(enterprise, command.getId());
        assertEquals(0, taskCount());
        assertEquals(1, outgoing("reply_settings").size());
        var setting = outgoing("reply_settings").getFirst();
        assertTrue(cipher.decrypt(enterprise, "outbound", setting.getId(), setting.getPayloadEncrypted()).path("replySettingsUrl").asText().contains("tab=robot-chat"));
        var choice = receive("default-choice", "默认仅发送链接");
        routing.route(enterprise, choice.getId());
        assertEquals("summary_link", preferences.get(actor(), group.getId()).outputVisibility());
        startTask("请检查这段文字中的链接");
        assertEquals("summary_link", onlyTask().getOutputVisibility());
        var task = onlyTask();
        var change = receive("task-choice", "任务 【" + task.getShortCode() + "】：本次直接回复内容");
        routing.route(enterprise, change.getId());
        assertEquals(1, taskCount());
        assertEquals(task.getInputRevision(), onlyTask().getInputRevision());
        assertEquals("chat_full", onlyTask().getOutputVisibility());
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, group.getId())
            .set(ChatConversationRow::getResultPolicy, "summary_link"));
        var blocked = receive("restricted", "默认直接回复内容");
        routing.route(enterprise, blocked.getId());
        assertTrue(outgoing("reply_settings").stream().anyMatch(row -> cipher.decrypt(enterprise, "outbound", row.getId(), row.getPayloadEncrypted())
            .at("/content/text").asText().contains("本群已设为仅发送链接")));
    }

    @Test
    void resendCompletedResultKeepsExecutionAndFilesAndUsesCurrentReplyMode() {
        startTask("生成合成文件");
        var active = startNext();
        var file = generated.register(generated.collaborationText(actor(), agent, version, active.run().id(), "合成成果内容"));
        assertFalse(invoke(active, "publish", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "sample",
            "title", "合成文件", "summary", "合成文件摘要", "kind", "file", "fileId", file.id())).path("isError").asBoolean());
        assertFalse(invoke(active, "finish", "collab_complete_task", Map.of("expectedInputRevision", 1, "summary", "**完整答案**",
            "artifactRefs", references(artifact("sample")), "acceptedPriorResults", List.of())).path("isError").asBoolean());
        lifecycle.finish(active.lease(), "completed", null, null);
        observer.observe(enterprise, link(active.run().id()).getId());
        var before = onlyTask();
        var request = new CollaborationResultResendRequest(UUID.randomUUID().toString(), before.getRevision(), 0L);
        var result = resends.send(actor(), before.getId(), request);
        assertEquals(result, resends.send(actor(), before.getId(), request));
        assertEquals(2, outgoing("completed_file").size());
        var row = databaseAccess.mapper(ChatOutboundMessageMapper.class).selectById((String) result.get("outboundId"));
        var lease = lease(row);
        assertEquals("**完整答案**", sending.prepare(lease).payload().at("/content/body/elements/0/content").asText());
        modes.save(actor(), before.getId(), new CollaborationReplyModeRequest("summary_link", 0L, false, null));
        assertFalse(sending.prepare(lease).payload().toString().contains("完整答案"));
        assertThrows(ApiException.class, () -> resends.send(actor(), before.getId(), request));
        assertEquals(before.getRunCount(), onlyTask().getRunCount());
        assertEquals(before.getInputRevision(), onlyTask().getInputRevision());
        assertEquals(before.getFinalArtifactId(), onlyTask().getFinalArtifactId());
        assertEquals("completed", onlyTask().getStatus());
    }

    @Test
    void ordinaryMemberApiHasCsrfAndIdempotencyWithoutManagementPermission() throws Exception {
        startTask("整理合成计划");
        var adminCookie = cookie;
        var adminCsrf = csrf;
        try {
            cookie = null;
            refreshCsrf();
            var login = write("/api/v1/auth/login", Map.of("identifier", initiator.username(), "password", "chat-isolated-user-password"), UUID.randomUUID().toString())
                .andExpect(status().isOk()).andReturn();
            cookie = login.getResponse().getCookie("SESSION");
            refreshCsrf();
            String path = base() + "/collaboration/tasks/" + onlyTask().getId() + "/reply-mode";
            mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk());
            mvc.perform(post(path).cookie(cookie).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
            var input = Map.of("outputVisibility", "summary_link", "expectedRevision", 0, "saveAsDefault", true, "expectedDefaultRevision", 0);
            String key = UUID.randomUUID().toString();
            var first = data(write(path, input, key).andExpect(status().isOk()).andReturn());
            assertEquals(first, data(write(path, input, key).andExpect(status().isOk()).andReturn()));
            assertEquals(1, modes.get(actor(), onlyTask().getId()).revision());
            mvc.perform(get(base() + "/chat/reply-preferences").cookie(cookie)).andExpect(status().isOk());
        } finally {
            cookie = adminCookie;
            csrf = adminCsrf;
        }
    }

    private AuthContext actor() {
        return identities.actor(enterprise, initiator.id(), binding.getId());
    }

    private List<ChatOutboundMessageRow> outgoing(String kind) {
        return databaseAccess.mapper(ChatOutboundMessageMapper.class).selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getKind, kind).orderByAsc(ChatOutboundMessageRow::getTaskSequence));
    }

    private ChatSendLease lease(ChatOutboundMessageRow row) {
        assertEquals(1, databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getId, row.getId())
            .set(ChatOutboundMessageRow::getStatus, "leased").set(ChatOutboundMessageRow::getLeaseOwner, "interaction-test")
            .set(ChatOutboundMessageRow::getLeaseVersion, 1L).set(ChatOutboundMessageRow::getLeaseUntil, Instant.now().plusSeconds(60))));
        return new ChatSendLease(enterprise, row.getId(), 1L, "interaction-test");
    }

    private void markSent(ChatOutboundMessageRow row) {
        var lease = lease(row);
        sending.begin(lease, sending.prepare(lease).payload());
        sending.complete(lease, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(ChannelSendResult.Outcome.ACCEPTED, "0",
            "om_" + row.getId(), null, null, null, 200, "synthetic-trace"), null));
    }

    private ObjectNode callback(ChatOutboundMessageRow row, String id, String action) {
        var event = json.createObjectNode();
        event.putObject("header").put("event_type", "card.action.trigger").put("event_id", id).put("app_id", bots.application(bot).externalAppId());
        var body = event.putObject("event");
        body.putObject("operator").put("open_id", "ou_user");
        body.putObject("context").put("open_message_id", "om_" + row.getId()).put("open_chat_id", externalChat);
        body.putObject("action").putObject("value").put("action", action);
        return event;
    }

    private JsonNode accept(JsonNode event) {
        return cards.accept(enterprise, bot.getId(), 1L, bots.application(bot).credentialRevision(), bot.getRevision(), event);
    }
}
