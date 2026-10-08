package com.stonewu.agenteam.mapper.agent;

import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelImageMapperTest {
    @Test
    void keepsAllToolRepliesBeforeSendingImageInputAndDoesNotChangeSavedMessages() {
        var image = ImageBlock.builder().source(new Base64Source("image/png", "test-data")).build();
        var first = ToolResultBlock.builder().id("first").name("view_image").output(List.of(TextBlock.builder().text("图片位置").build(), image)).build();
        var second = ToolResultBlock.builder().id("second").name("read_file").output(TextBlock.builder().text("第二个工具结果").build()).build();
        var messages = List.of(Msg.builderForRole(MsgRole.TOOL).content(first).build(), Msg.builderForRole(MsgRole.TOOL).content(second).build());
        var mapped = ModelImageMapper.withImageInputs(messages);
        assertEquals(3, mapped.size());
        assertEquals("first", ((ToolResultBlock) mapped.get(0).getContent().getFirst()).getId());
        assertEquals("second", ((ToolResultBlock) mapped.get(1).getContent().getFirst()).getId());
        assertEquals(MsgRole.USER, mapped.get(2).getRole());
        assertTrue(mapped.get(2).getContent().contains(image));
        assertEquals(2, first.getOutput().size());
        var textOnly = ModelImageMapper.withoutToolImages(messages);
        assertFalse(((ToolResultBlock) textOnly.getFirst().getContent().getFirst()).getOutput().stream().anyMatch(ImageBlock.class::isInstance));
    }
}
