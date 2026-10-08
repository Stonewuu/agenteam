package com.stonewu.agenteam.mapper.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.plugin.PlatformToolDefinitions;
import com.stonewu.agenteam.mapper.plugin.PluginToolDefinitionMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolViewMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolDisplayNameMapperTest {
    private final ResourceJson json = new ResourceJson(new ObjectMapper());
    private final PlatformToolDefinitions platform = new PlatformToolDefinitions(json);

    @Test
    void builtinNamesCoverEveryRegisteredActionWithoutChangingCallNames() {
        var groups = Map.of("platform_basics", platform.basics(), "todo_management", platform.todos(), "schedule_management", platform.schedules());
        groups.forEach((code, tools) -> tools.forEach(tool -> {
            var config = json.tree(Map.of("pluginType", "builtin", "builtinCode", code));
            var view = new PluginToolViewMapper(json).map("tool", tool, true, config);
            assertNotEquals(tool.name(), view.displayName());
            assertTrue(view.displayName().codePoints().anyMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN));
            assertEquals(tool.name(), view.name());
        }));
        assertEquals("读取网页", ToolDisplayNameMapper.name(definition("read_url", Map.of()), "plugin", json.tree(Map.of("pluginType", "builtin", "builtinCode", "web_read"))));
    }

    @Test
    void approvalTitleIsChineseWhileArgumentsAndActualTargetRemainUnchanged() {
        var tool = platform.todos().stream().filter(item -> item.name().equals("todo_create")).findFirst().orElseThrow();
        var binding = new ExecutionToolBinding("resource", "version", "plugin", "待办管理", "tool", tool,
            json.tree(Map.of("pluginType", "builtin", "builtinCode", "todo_management")));
        var arguments = json.tree(Map.of("title", "整理工作计划"));
        var summary = new ToolApprovalSummaryMapper().map(binding, arguments);
        assertEquals("待办管理 · 创建待办", summary.title());
        assertEquals(summary.title(), summary.target());
        assertEquals(arguments.toString(), summary.content());
        assertEquals("todo_create", binding.definition().name());
        assertEquals("地址：https://example.com", new ToolApprovalSummaryMapper().map(binding, json.tree(Map.of("url", "https://example.com"))).target());
    }

    @Test
    void remoteTitleIsPreservedAndDoesNotChangeInvocationSchemaHash() {
        var mapper = new PluginToolDefinitionMapper(json, new ToolSchemaValidation());
        ObjectNode raw = json.tree(Map.of("name", "ask_question", "title", "查询仓库文档", "inputSchema", Map.of("type", "object"),
            "annotations", Map.of("title", "旧展示名", "readOnlyHint", true))).deepCopy();
        var tool = mapper.remote(List.of(raw), 30).getFirst();
        assertEquals("查询仓库文档", ToolDisplayNameMapper.name(tool, "plugin", null));
        assertEquals("ask_question", tool.name());
        assertEquals("旧展示名", raw.path("annotations").path("title").asText());
        assertTrue(tool.annotations().path("readOnlyHint").asBoolean());
        raw.put("title", "查阅公开文档");
        assertEquals(tool.schemaHash(), mapper.remote(List.of(raw), 30).getFirst().schemaHash());
        raw.remove("title");
        assertEquals("旧展示名", ToolDisplayNameMapper.name(mapper.remote(List.of(raw), 30).getFirst(), "plugin", null));
    }

    @Test
    void externalSameNameDoesNotAcquireBuiltinTitleAndUnknownNamesRemainIdentifiable() {
        var remote = definition("todo_create", Map.of());
        assertEquals("todo_create", ToolDisplayNameMapper.name(remote, "plugin", json.tree(Map.of("pluginType", "mcp"))));
        assertEquals("todo_create", ToolDisplayNameMapper.name(remote, "plugin", json.tree(Map.of("pluginType", "builtin", "builtinCode", "web_read"))));
        assertEquals("自定义事项", ToolDisplayNameMapper.name(definition("todo_create", Map.of("title", "自定义事项")), "plugin", null));
        assertEquals("unknown_tool", ToolDisplayNameMapper.name(definition("unknown_tool", Map.of("title", " ")), "plugin", null));
        assertEquals("查询数据", ToolDisplayNameMapper.name(definition("data_query", Map.of()), "data", null));
        assertEquals("检索资料", ToolDisplayNameMapper.name(definition("knowledge_search", Map.of()), "knowledge", null));
    }

    private ToolDefinition definition(String name, Map<String, Object> annotations) {
        return new ToolDefinition(name, "工具说明", "hash", json.tree(Map.of("type", "object")), null, json.tree(annotations), "read", false, false, false, List.of(), 30);
    }
}
