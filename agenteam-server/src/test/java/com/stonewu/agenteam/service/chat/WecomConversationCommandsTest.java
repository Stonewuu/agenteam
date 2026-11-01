package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WecomConversationCommandsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void standaloneAndInlineNewCommandsWorkAfterConfirmedMentions() {
        var mentions = json.createArrayNode();
        mentions.addObject().put("placeholder", "@_user_1").put("name", "助手");
        var command = WecomConversationCommands.parse(" @_user_1 /new\n分析这份材料", mentions);
        assertTrue(command.newConversation());
        assertEquals("分析这份材料", command.input());
        assertTrue(WecomConversationCommands.parse("@助手 /new", mentions).input().isEmpty());
        assertTrue(WecomConversationCommands.parse("@助手 /history", mentions).history());
    }

    @ParameterizedTest
    @ValueSource(strings = {"解释 /new 命令", "/newspaper", "/new:问题", "`/new`", "/history 其他人", "转述：/history", "@陌生机器人 /new"})
    void ordinaryTextAndUnconfirmedMentionsDoNotChangeTheConversation(String text) {
        var result = WecomConversationCommands.parse(text, json.createArrayNode());
        assertFalse(result.newConversation());
        assertFalse(result.history());
        assertEquals(text, result.input());
    }

    @Test
    void wecomCallbacksCanPutTheKnownBotNameBeforeOrAfterTheCommand() {
        var mentions = json.createArrayNode();
        mentions.addObject().put("kind", "bot").put("externalId", "known-bot").put("name", "").putNull("placeholder");
        assertTrue(WecomConversationCommands.parse("@测试助手 B /history", mentions, "known-bot", "测试助手 B").history());
        assertTrue(WecomConversationCommands.parse("/new@测试助手 B ", mentions, "known-bot", "测试助手 B").newConversation());
        var withQuestion = WecomConversationCommands.parse("@测试助手 B /new\n分析材料", mentions, "known-bot", "测试助手 B");
        assertEquals("分析材料", withQuestion.input());
        assertFalse(WecomConversationCommands.parse("@陌生助手 /history", mentions, "known-bot", "测试助手 B").history());
        assertFalse(WecomConversationCommands.parse("@测试助手 B /history", mentions, "another-bot", "测试助手 B").history());
        assertEquals("分析材料", WecomConversationCommands.withoutOwnMention("@测试助手 B 分析材料", "测试助手 B"));
    }

    @Test
    void anotherMemberCannotChooseOrTurnPagesInSomeoneElsesHistory() {
        var tasks = mock(CollaborationTaskMapper.class);
        var outgoing = mock(ChatOutboundService.class);
        var sessions = mock(ChatSessionSelectionService.class);
        var service = new WecomConversationHistoryService(tasks, mock(ChatOutboundMessageMapper.class), outgoing,
            mock(ChatPayloadCipher.class), new ResourceJson(json), sessions, mock(CollaborationAccessService.class));
        var actor = mock(AuthContext.class);
        when(actor.userId()).thenReturn("another-member");
        var payload = json.createObjectNode().put("historyUserId", "original-member").put("historyHasNext", true).put("historyPage", 1);
        assertFalse(service.accept(actor, new ChatConversationRow(), new ChatBotBindingRow(), payload, "history_next", "receipt", Instant.now()));
        assertFalse(service.accept(actor, new ChatConversationRow(), new ChatBotBindingRow(), payload, "history_choose_0", "receipt", Instant.now()));
        verifyNoInteractions(tasks, outgoing, sessions);
    }
}
