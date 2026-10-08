package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.service.file.Utf8Text;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultHistoryTest {
    @Test
    void shortensSavedOldResultsWithoutLosingLatestBatchOrToolPairing() {
        var mapper = new ObjectMapper();
        var json = new ResourceJson(mapper);
        var value = mapper.createObjectNode().put("content", "完整结果🙂".repeat(2500));
        value.set(ToolResultContent.REFERENCE, ToolResultContent.reference("saved", "saved", value, 40000, 2048));
        String full = value.toString();
        var user = Msg.builder().role(MsgRole.USER).textContent("继续分析原来的文件").build();
        var old = result("old", full);
        var current = result("latest", full);
        var use = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("latest").name("platform_read_url_version").input(Map.of()).build()).build();
        var messages = List.of(user, old, use, current);
        var shortened = ToolResultHistory.shorten(messages, ToolResultHistory.latestCalls(messages), json, 2048);
        assertSame(user, shortened.get(0));
        assertSame(use, shortened.get(2));
        assertSame(current, shortened.get(3));
        var before = (ToolResultBlock) old.getContent().getFirst();
        var after = (ToolResultBlock) shortened.get(1).getContent().getFirst();
        assertEquals(before.getId(), after.getId());
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getState(), after.getState());
        String reference = ((TextBlock) after.getOutput().getFirst()).getText();
        assertTrue(Utf8Text.size(reference) <= 2048);
        assertEquals("tool-results/saved/content.txt", json.read(reference).path("path").asText());
        assertEquals(full, ((TextBlock) before.getOutput().getFirst()).getText());
        var nextQuestion = new ArrayList<>(messages);
        nextQuestion.add(Msg.builder().role(MsgRole.ASSISTANT).textContent("本轮已完成").build());
        nextQuestion.add(Msg.builder().role(MsgRole.USER).textContent("继续下一轮").build());
        assertTrue(ToolResultHistory.latestCalls(nextQuestion).isEmpty(), "新一轮提问不能让上轮工具全文继续占用最近结果名额");
        var nextHistory = ToolResultHistory.shorten(nextQuestion, Set.of(), json, 2048);
        var oldCurrent = (ToolResultBlock) nextHistory.get(3).getContent().getFirst();
        assertTrue(Utf8Text.size(((TextBlock) oldCurrent.getOutput().getFirst()).getText()) <= 2048);
    }

    private Msg result(String id, String content) {
        return Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.builder().id(id).name("platform_read_url_version").output(TextBlock.builder().text(content).build()).build()).build();
    }
}
