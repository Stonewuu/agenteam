package com.stonewu.agenteam.mapper.agent;

import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import com.stonewu.agenteam.model.modelprofile.entity.NativeModelState;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStreamEventMapperTest {

    @Test
    void preservesCommonAndEventSpecificFields() {
        AgentStreamEvent mapped = new AgentStreamEventMapper()
            .map(new AgentStartEvent("session-1", "reply-1", "harness-agent"));

        assertEquals("AGENT_START", mapped.type());
        assertEquals("reply-1", mapped.replyId());
        assertNotNull(mapped.createdAt());
        assertEquals("session-1", mapped.details().get("sessionId"));
        assertEquals("harness-agent", mapped.details().get("name"));
        assertEquals("assistant", mapped.details().get("role"));
    }

    @Test
    void keepsCompleteAgentResultPayload() {
        AgentStreamEvent mapped = new AgentStreamEventMapper()
            .map(new AgentResultEvent(new UserMessage("result text")));

        assertEquals("result text", mapped.text());
        assertNotNull(mapped.details().get("result"));
    }

    @Test
    void preservesSubagentSourcePath() {
        AgentStreamEvent mapped = new AgentStreamEventMapper()
            .map(new AgentStartEvent("session-1", "reply-1", "researcher")
                .withSource("main/researcher"));

        assertEquals("main/researcher", mapped.source());
    }

    @Test
    void nativeReasoningStateStaysInTheMessageButNeverEntersStoredEvents() throws Exception {
        var state = new NativeModelState(1, "anthropic", "provider", "model", List.of(
            new NativeModelState.Item("thinking", List.of(), null, Map.of("signature", "private-signature"))));
        var message = AssistantMessage.builder().content(TextBlock.builder().text("公开回答").build())
            .metadata(Map.of(NativeModelState.METADATA_KEY, state, "ordinary", "keep-this")).build();
        String event = new ObjectMapper().writeValueAsString(new AgentStreamEventMapper().map(new AgentResultEvent(message)));
        assertTrue(message.getMetadata().containsKey(NativeModelState.METADATA_KEY));
        assertFalse(event.contains("private-signature"));
        assertFalse(event.contains(NativeModelState.METADATA_KEY));
        assertTrue(event.contains("keep-this"));
        assertTrue(event.contains("公开回答"));
    }

    @Test
    void officialNestedSignaturesAreRemovedOnlyFromTheVisibleCopy() throws Exception {
        var thinking = ThinkingBlock.builder().thinking("可见摘要")
            .metadata(Map.of("openai.reasoning.encrypted_content", "opaque-private-state",
                "openai.reasoning.text", "private-reasoning", "ordinary", "retain-metadata")).build();
        var tool = ToolUseBlock.builder().id("call-1").name("echo").input(Map.of("value", "测试"))
            .metadata(Map.of(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, "private-signature")).build();
        var message = AssistantMessage.builder().content(List.of(thinking, tool)).build();
        String event = new ObjectMapper().writeValueAsString(new AgentStreamEventMapper().map(new AgentResultEvent(message)));
        assertFalse(event.contains("opaque-private-state"));
        assertFalse(event.contains("private-reasoning"));
        assertFalse(event.contains("private-signature"));
        assertTrue(event.contains("可见摘要"));
        assertTrue(event.contains("retain-metadata"));
        assertEquals("opaque-private-state", thinking.getMetadata().get("openai.reasoning.encrypted_content"));
        assertEquals("private-signature", tool.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
    }
}
