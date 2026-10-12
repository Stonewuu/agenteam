package com.stonewu.agenteam.service.modelprofile.protocol;

import com.stonewu.agenteam.mapper.agent.AgentStreamEventMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelHistoryOrigin;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelProtocolHistoryTest {
    @Test
    void restoredOriginRetainsSignaturesOnlyForTheExactProviderAndModel() {
        var history = new ModelProtocolHistory("openai_responses", "provider", "model");
        var frame = history.identify(ChatResponse.builder().content(List.of(
            ThinkingBlock.builder().thinking("可见思考").metadata(Map.of("openai.reasoning.encrypted_content", "private-content",
                ThinkingBlock.METADATA_REASONING_DETAILS, List.of("private-detail"))).build(),
            ToolUseBlock.builder().id("call-one").name("mapped_tool").input(Map.of("value", "数据"))
                .metadata(Map.of(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, "private-signature")).build())).build());
        var message = AssistantMessage.builder().content(frame.getContent()).metadata(frame.getMetadata()).build();
        var restored = JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(message), Msg.class);
        assertSame(restored, history.prepare(List.of(restored)).getFirst());
        var other = new ModelProtocolHistory("openai_responses", "another-provider", "model")
            .prepare(List.of(restored)).getFirst();
        assertFalse(other.getMetadata().containsKey(ModelHistoryOrigin.METADATA_KEY));
        assertTrue(other.getContentBlocks(ThinkingBlock.class).getFirst().getMetadata().isEmpty());
        var tool = other.getContentBlocks(ToolUseBlock.class).getFirst();
        assertTrue(tool.getMetadata().isEmpty());
        assertEquals("mapped_tool", tool.getName());
        assertEquals(Map.of("value", "数据"), tool.getInput());
        assertEquals(restored.getId(), other.getId());
        assertTrue(restored.getContentBlocks(ThinkingBlock.class).getFirst().getMetadata()
            .containsKey("openai.reasoning.encrypted_content"));
        String event = JsonUtils.getJsonCodec().toJson(new AgentStreamEventMapper().map(new AgentResultEvent(restored)));
        assertFalse(event.contains("private-"));
        assertFalse(event.contains(ModelHistoryOrigin.METADATA_KEY));
        assertTrue(event.contains("可见思考"));
    }

    @Test
    void unknownSignaturesAreNotReplayedWhileOrdinaryMetadataSurvives() {
        var message = AssistantMessage.builder().content(ThinkingBlock.builder().thinking("历史摘要")
            .metadata(Map.of("openai.reasoning.encrypted_content", "unknown-state", "ordinary", "keep")).build()).build();
        var prepared = new ModelProtocolHistory("anthropic", "provider", "model").prepare(List.of(message)).getFirst();
        assertEquals(Map.of("ordinary", "keep"), prepared.getContentBlocks(ThinkingBlock.class).getFirst().getMetadata());
        assertEquals("历史摘要", prepared.getContentBlocks(ThinkingBlock.class).getFirst().getThinking());
    }
}
