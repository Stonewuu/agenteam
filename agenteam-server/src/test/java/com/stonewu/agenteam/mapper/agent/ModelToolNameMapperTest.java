package com.stonewu.agenteam.mapper.agent;

import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ModelToolNameMapperTest {
    @Test
    void originalNamesAreSentToTheModelWhileReturnedCallsKeepTheirInternalIdentity() {
        String alias = "platform_" + "a".repeat(32);
        var tools = List.of(schema(alias));
        var arguments = Map.<String, Object>of("literal", alias);
        var call = ToolUseBlock.builder().id("call").name(alias).input(arguments).content("{\"literal\":\"原文\"}").metadata(Map.of("signature", "保持签名")).build();
        var result = ToolResultBlock.builder().id("call").name(alias).output(TextBlock.builder().text("结果原文").build()).build();
        var history = List.of(Msg.builder().role(MsgRole.ASSISTANT).content(call).build(), Msg.builder().role(MsgRole.TOOL).content(result).build());
        var mapper = new ModelToolNameMapper(tools, history, Map.of(alias, "todo_create"));
        assertEquals("todo_create", mapper.tools(tools).getFirst().getName());
        var modelCall = (ToolUseBlock) mapper.messages(history).getFirst().getContent().getFirst();
        assertEquals("todo_create", modelCall.getName());
        assertEquals(arguments, modelCall.getInput());
        assertEquals("todo_create", ((ToolResultBlock) mapper.messages(history).getLast().getContent().getFirst()).getName());
        assertEquals(alias, call.getName(), "历史原记录不得被修改");
        var restored = (ToolUseBlock) mapper.response(ChatResponse.builder().content(List.of(modelCall)).finishReason("tool_calls").metadata(Map.of("trace", "原数据")).build()).getContent().getFirst();
        assertEquals(alias, restored.getName());
        assertEquals("call", restored.getId());
        assertEquals(call.getMetadata(), restored.getMetadata());
        var options = mapper.options(GenerateOptions.builder().temperature(0.3).maxTokens(100).toolChoice(new ToolChoice.Specific(alias)).build());
        assertEquals(new ToolChoice.Specific("todo_create"), options.getToolChoice());
        assertEquals(100, options.getMaxTokens());
        assertEquals(0.3, options.getTemperature());
    }

    @Test
    void collidingNamesUseShortDeterministicSuffixesWithoutTakingAnotherToolsRealName() {
        var tools = List.of(schema("second"), schema("first"), schema("search_1"), schema("agent_spawn"), schema("reserved"));
        var preferred = Map.of("second", "search", "first", "search", "reserved", "agent_spawn");
        var mapper = new ModelToolNameMapper(tools, List.of(), preferred);
        var names = mapper.tools(tools).stream().map(ToolSchema::getName).collect(Collectors.toSet());
        assertEquals(Set.of("search_2", "search_3", "search_1", "agent_spawn", "agent_spawn_1"), names);
        var reversed = new ModelToolNameMapper(tools.reversed(), List.of(), preferred);
        assertEquals(mapper.tools(tools).stream().map(ToolSchema::getName).toList(), reversed.tools(tools).stream().map(ToolSchema::getName).toList());
        var blocks = names.stream().map(name -> ToolUseBlock.builder().id(name).name(name).build()).toList();
        var restored = mapper.response(ChatResponse.builder().content(List.copyOf(blocks)).build());
        assertEquals(tools.stream().map(ToolSchema::getName).collect(Collectors.toSet()), restored.getContent().stream().map(block -> ((ToolUseBlock) block).getName()).collect(Collectors.toSet()));
    }

    @Test
    void argumentFragmentsAndUnknownNamesAreNotMappedToAnotherTool() {
        var mapper = new ModelToolNameMapper(List.of(schema("internal")), List.of(), Map.of("internal", "todo_create"));
        var fragment = ToolUseBlock.builder().id("").name("__fragment__").content("标题\"}").build();
        var unknown = ToolUseBlock.builder().id("wrong").name("todo_delete").build();
        var response = mapper.response(ChatResponse.builder().content(List.of(fragment, unknown)).build());
        assertSame(fragment, response.getContent().getFirst());
        assertSame(unknown, response.getContent().getLast());
    }

    @Test
    void oldHistoryDoesNotRenameCurrentToolsAndLongNamesRemainWithinTheProtocolLimit() {
        var history = List.of(Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("old").name("old-id").build()).build());
        var tools = List.of(schema("new-id"), schema("long-a"), schema("long-b"));
        var mapper = new ModelToolNameMapper(tools, history, Map.of("old-id", "lookup", "new-id", "lookup", "long-a", "a".repeat(120) + ".x", "long-b", "a".repeat(120) + ".y"));
        var names = mapper.tools(tools).stream().map(ToolSchema::getName).toList();
        assertEquals("lookup", names.getFirst());
        assertEquals(3, names.stream().distinct().count());
        assertTrue(names.stream().allMatch(name -> name.matches("[a-zA-Z0-9_-]{1,64}")));
        assertEquals("lookup_1", ((ToolUseBlock) mapper.messages(history).getFirst().getContent().getFirst()).getName());
    }

    private ToolSchema schema(String name) {
        return ToolSchema.builder().name(name).description("验证工具名称").parameters(Map.of("type", "object")).build();
    }
}
