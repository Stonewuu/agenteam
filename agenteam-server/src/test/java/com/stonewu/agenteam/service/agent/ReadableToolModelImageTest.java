package com.stonewu.agenteam.service.agent;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadableToolModelImageTest {
    @Test
    void textModelReceivesOriginalTextAndReturnsItsOwnAnswerWithoutImageWarnings() {
        var delegate = mock(AgentModelFactory.ConfiguredModel.class);
        var image = ImageBlock.builder().source(new Base64Source("image/png", "test-image")).build();
        var question = Msg.builderForRole(MsgRole.USER).content(TextBlock.builder().text("请看看这张图片").build(), image).build();
        when(delegate.stream(anyList(), anyList(), any())).thenAnswer(call -> {
            List<Msg> sent = call.getArgument(0);
            assertEquals(1, sent.size());
            assertEquals("请看看这张图片", sent.getFirst().getTextContent());
            assertFalse(sent.getFirst().getContent().stream().anyMatch(ImageBlock.class::isInstance));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("模型自己的回答").build())).build());
        });
        var model = new ReadableToolModel(delegate, Map::of, ignored -> {
            throw new AssertionError("文字模型不应加载图片内容");
        });
        var answer = model.stream(List.of(question), List.of(), GenerateOptions.builder().build()).blockLast();
        assertNotNull(answer);
        assertEquals("模型自己的回答", ((TextBlock) answer.getContent().getFirst()).getText());
        assertEquals(2, question.getContent().size());
    }

    @Test
    void imageModelReceivesImageWithoutAnAdditionalTextPrompt() {
        var delegate = mock(AgentModelFactory.ConfiguredModel.class);
        when(delegate.supportsImages()).thenReturn(true);
        var question = Msg.builderForRole(MsgRole.USER).id("user-message").textContent("图片里是什么？").build();
        var image = ImageBlock.builder().source(new Base64Source("image/png", "image-data")).build();
        when(delegate.stream(anyList(), anyList(), any())).thenAnswer(call -> {
            List<Msg> sent = call.getArgument(0);
            assertEquals(1, sent.size());
            assertEquals("图片里是什么？", sent.getFirst().getTextContent());
            assertTrue(sent.getFirst().getContent().contains(image));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("图片内容").build())).build());
        });
        var model = new ReadableToolModel(delegate, Map::of, messages -> {
            var content = new ArrayList<>(messages.getFirst().getContent());
            content.add(image);
            return List.of(Msg.builderForRole(MsgRole.USER).id("user-message").content(content).build());
        });
        assertNotNull(model.stream(List.of(question), List.of(), GenerateOptions.builder().build()).blockLast());
        assertEquals(1, question.getContent().size());
    }
}
