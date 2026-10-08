package com.stonewu.agenteam.mapper.agent;

import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
