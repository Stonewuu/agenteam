package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 使用已规范化的平台回调验证企微提问按钮的原群、凭据与用户检查。 */
class WecomQuestionCardTest {
    @Test
    void acceptsVerifiedFreeInputButRejectsForwardingAndOldCredentials() {
        var json = new ObjectMapper();
        var messages = mock(ChatOutboundMessageMapper.class);
        var conversations = mock(ChatConversationService.class);
        var bots = mock(ChatBotBindingService.class);
        var identities = mock(ChatIdentityService.class);
        var cipher = mock(ChatPayloadCipher.class);
        var replies = mock(ChatQuestionReplyService.class);
        var outbound = mock(ChatOutboundService.class);
        var sessions = mock(ChatSessionSelectionService.class);
        var service = new WecomQuestionCardService(messages, conversations, bots, identities, cipher, replies, outbound, sessions);
        var bot = new ChatBotBindingRow();
        bot.setId("bot"); bot.setEnterpriseId("enterprise"); bot.setConnectionId("connection"); bot.setChatEnabled(true);
        var source = new ChatOutboundMessageRow();
        source.setId("source"); source.setConversationId("group"); source.setRenderedPayloadEncrypted("encrypted"); source.setTaskId("task");
        when(messages.selectOne(any())).thenReturn(source);
        var group = new ChatConversationRow();
        group.setId("group"); group.setStatus("enabled"); group.setConversationKind("group");
        when(conversations.require("enterprise", "group")).thenReturn(group);
        var member = new ChatConversationBotRow();
        member.setMembershipStatus("present"); member.setReceiveEnabled(true); member.setSendEnabled(true); member.setExternalChatId("wecom-group");
        when(conversations.member("enterprise", "group", "bot")).thenReturn(member);
        when(bots.application(bot)).thenReturn(new IntegrationApplication("connection", "enterprise", "wecom_aibot", null, "external-bot", 2));
        when(bots.currentCredentials(bot, 2)).thenReturn(true);
        var payload = json.createObjectNode().put("credentialRevision", 2);
        when(cipher.decrypt("enterprise", "outbound-rendered", "source", "encrypted")).thenReturn(payload);
        var binding = new UserChannelBindingRow();
        binding.setId("binding"); binding.setUserId("user");
        when(identities.find("enterprise", "connection", "wecom_userid", "member")).thenReturn(Optional.of(binding));
        var actor = mock(AuthContext.class);
        when(identities.actor("enterprise", "user", "binding")).thenReturn(actor);
        var receipt = new ChatInboundReceiptRow();
        receipt.setId("receipt"); receipt.setConnectionRevision(2L);
        var event = mock(NormalizedChatEvent.class);
        when(event.providerCode()).thenReturn("wecom_aibot"); when(event.senderKind()).thenReturn("user");
        when(event.chatKind()).thenReturn("group"); when(event.externalChatId()).thenReturn("another-group");
        when(event.subjectType()).thenReturn("wecom_userid"); when(event.senderId()).thenReturn("member");
        when(event.metadata()).thenReturn(json.createObjectNode().put("event_key", "question_free_text").put("task_id", "source"));
        assertFalse(service.accept(bot, receipt, event));
        verifyNoInteractions(replies);
        when(event.externalChatId()).thenReturn("wecom-group");
        receipt.setConnectionRevision(1L);
        assertFalse(service.accept(bot, receipt, event));
        verifyNoInteractions(replies);
        receipt.setConnectionRevision(2L);
        assertTrue(service.accept(bot, receipt, event));
        verify(replies).card(actor, source, payload, "question_free_text", null);
        verify(sessions).select(actor, "group", "bot", "task", receipt.getReceivedAt());
        verify(outbound).text(eq("enterprise"), eq("group"), eq("bot"), eq("task"), isNull(), eq("question-reply:receipt"),
            eq("question_answered"), eq("请 @ 我输入这个问题的回答。"), isNull());
        clearInvocations(outbound, replies);
        when(event.externalChatId()).thenReturn("another-group");
        service.rejected(bot, receipt, event, "QUESTION_ALREADY_ANSWERED");
        verifyNoInteractions(outbound, replies);
        when(event.externalChatId()).thenReturn("wecom-group");
        service.rejected(bot, receipt, event, "QUESTION_ALREADY_ANSWERED");
        verify(replies).requireCardQuestion(actor, source, payload);
        verify(outbound).text(eq("enterprise"), eq("group"), eq("bot"), isNull(), isNull(), eq("question-rejected:receipt"),
            eq("question_rejected"), eq("这个问题已经回答，无需再次选择。"), isNull());
        clearInvocations(outbound);
        when(replies.requireCardQuestion(actor, source, payload)).thenThrow(
            new ApiException(HttpStatus.FORBIDDEN, "QUESTION_UNAVAILABLE", "无法回答这个问题。"));
        service.rejected(bot, receipt, event, "QUESTION_UNAVAILABLE");
        verifyNoInteractions(outbound);
    }
}
