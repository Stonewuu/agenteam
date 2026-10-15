package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatMentionDisplayTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void knownMentionsRenderWithoutCorruptingLongerUnknownPlaceholdersOrRegexCharacters() {
        var mentions = json.createArrayNode();
        mentions.addObject().put("placeholder", "@_user_1").put("name", "财务$复核");
        mentions.addObject().put("placeholder", "@_user_2").put("name", "人事");
        assertEquals("@财务$复核 @人事你好 @_user_10 @_user_3",
            ChatCommandText.display("@_user_1 @_user_2你好 @_user_10 @_user_3", mentions));
    }

    @Test
    void newTaskCommandWorksBeforeAndAfterMentionRendering() {
        var mentions = json.createArrayNode().addObject().put("placeholder", "@_user_1").put("name", "人事");
        var values = json.createArrayNode().add(mentions);
        assertTrue(ChatCommandText.newTask("@_user_1 新任务：核对考勤", values));
        assertTrue(ChatCommandText.newTask(ChatCommandText.display("@_user_1 新任务：核对考勤", values), values));
        assertFalse(ChatCommandText.newTask("@未知岗位 新任务：核对考勤", values));
    }
}
