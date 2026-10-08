package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRecord;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.plugin.BuiltinPluginAdapter;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 从真实目录选择、发布和执行合集，覆盖同名工具及来源权限变化。
 */
@Import({SharedEnterpriseTestEdition.class, ToolCollectionApiTest.ExtraSources.class})
class ToolCollectionApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private PluginToolMapper pluginTools;
    @Autowired
    private ToolCallMapper calls;
    @Autowired
    private RunMapper runs;
    @Autowired
    private ResourceMapper resources;
    @Autowired
    private ResourceVersionMapper versions;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry properties) {
        ENVIRONMENT.properties(properties);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void toolsFromThreeCapabilitiesAreTheOnlyExposedAndExecutedActions() throws Exception {
        var directory = directory();
        directory.forEach(source -> schemas.validate("ToolSource", source));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "tool-collection-directory.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(directory));
        var config = configuration(directory, Map.of("todo_management", List.of("todo_list"), "schedule_management", List.of("schedule_list"), "platform_basics", List.of("platform_time")));
        var bundle = publish(config);
        var selected = pluginTools.list(enterprise, bundle);
        assertEquals(3, selected.size());
        assertTrue(selected.stream().allMatch(tool -> tool.source() != null && tool.entryId() != null));
        configureAgent(agent -> agent.set("pluginVersionIds", json.valueToTree(List.of(bundle))));
        model(selected, Map.of());
        String run = submit();
        finish(run);
        var actual = calls.forRun(enterprise, run);
        assertEquals(Set.of("todo_list", "schedule_list", "platform_time"), actual.stream().map(call -> call.toolName()).collect(Collectors.toSet()));
        assertTrue(actual.stream().allMatch(call -> call.status().equals("succeeded")));
    }

    @Test
    void identicallyNamedToolsHaveDifferentAliasesAndExecuteTheirOwnSource() throws Exception {
        String bundle = publish(configuration(directory(), Map.of("collection_alpha", List.of("lookup"), "collection_beta", List.of("lookup"))));
        var tools = pluginTools.list(enterprise, bundle);
        assertEquals(2, tools.size());
        assertNotEquals(tools.get(0).id(), tools.get(1).id());
        configureAgent(agent -> agent.set("pluginVersionIds", json.valueToTree(List.of(bundle))));
        model(tools, Map.of());
        String run = submit();
        finish(run);
        var results = calls.forRun(enterprise, run).stream().map(call -> call.resultRedacted().path("provider").asText()).collect(Collectors.toSet());
        assertEquals(Set.of("collection_alpha", "collection_beta"), results);
        var options = data(mvc.perform(get(base() + "/workflows/dependency-options/" + bundle).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("tools");
        assertEquals(2, options.size());
        assertNotEquals(options.get(0).path("entryId"), options.get(1).path("entryId"));
    }

    @Test
    void disablingAnOriginalSourceAfterConfirmationPreventsBusinessWrite() throws Exception {
        var config = configuration(directory(), Map.of("todo_management", List.of("todo_create")));
        String bundle = publish(config);
        configureAgent(agent -> agent.set("pluginVersionIds", json.valueToTree(List.of(bundle))));
        model(pluginTools.list(enterprise, bundle), Map.of("title", "来源停用后不得创建"));
        String run = submit();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("waiting_approval"));
        }
        var approval = data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", "approve", "requestHash", approval.path("requestHash").asText()), approval.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        var source = resources.find(enterprise, config.at("/sources/0/id").asText(), false, false).orElseThrow();
        resources.status(source, "disabled", Instant.now());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertNotEquals("completed", state(run));
        assertEquals(0, count("todo_item"));
        assertTrue(calls.forRun(enterprise, run).stream().allMatch(call -> call.submittedAt() == null));
    }

    @Test
    void workflowExecutesOnlyTheSelectedEntryWhenSourceMethodNamesMatch() throws Exception {
        String bundle = publish(configuration(directory(), Map.of("collection_alpha", List.of("lookup"), "collection_beta", List.of("lookup"))));
        var selected = pluginTools.list(enterprise, bundle).stream().filter(tool -> tool.source().config().path("builtinCode").asText().equals("collection_beta")).findFirst().orElseThrow();
        var graph = json.createObjectNode().put("icon", "GitBranch").put("color", "blue");
        var nodes = graph.putArray("nodes");
        nodes.add(node("start", "start", Map.of("inputSchema", Map.of("type", "object"))));
        nodes.add(node("work", "tool", Map.of("pluginVersionId", bundle, "toolId", selected.entryId(), "inputMapping", Map.of())));
        nodes.add(node("end", "end", Map.of("outputMapping", Map.of())));
        graph.putArray("edges").add(json.valueToTree(Map.of("edgeId", "one", "source", "start", "target", "work", "branch", "default")))
            .add(json.valueToTree(Map.of("edgeId", "two", "source", "work", "target", "end", "branch", "default")));
        var created = data(write(base() + "/resources", Map.of("kind", "workflow", "name", "同名工具流程", "description", "按条目选择第二个来源", "tagIds", List.of(), "config", graph), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String id = created.at("/resource/id").asText();
        var accepted = data(change(HttpMethod.POST, base() + "/workflows/" + id + "/preview", Map.of("draft", graph, "input", Map.of()), "1", UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        finish(run);
        var actual = calls.forRun(enterprise, run);
        assertEquals(1, actual.size());
        assertEquals(selected.id(), actual.getFirst().pluginToolId());
        assertEquals("collection_beta", actual.getFirst().resultRedacted().path("provider").asText());
    }

    @Test
    void publishedCollectionKeepsItsOriginalSourceVersionAndRejectsRevokedVersions() throws Exception {
        var config = configuration(directory(), Map.of("platform_basics", List.of("platform_time")));
        String original = config.at("/sources/0/versionId").asText(), bundle = publish(config);
        var resource = resources.find(enterprise, config.at("/sources/0/id").asText(), false, false).orElseThrow();
        String next = UUID.randomUUID().toString();
        versions.publish(next, resource, "验证新版本", admin, List.of(), Instant.now());
        pluginTools.publish(enterprise, next, pluginTools.list(enterprise, original).stream().map(PluginToolRecord::definition).toList(), Instant.now());
        resources.publish(resource, next, Instant.now());
        assertEquals(original, pluginTools.list(enterprise, bundle).getFirst().source().versionId());
        var old = data(mvc.perform(get(base() + "/plugins/tool-sources/" + resource.id() + "/versions/" + original).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(original, old.path("versionId").asText());
        versions.revoke(enterprise, next, Instant.now());
        var latest = directory();
        for (var source : latest) {
            if (source.path("resourceId").asText().equals(resource.id())) {
                assertEquals(original, source.path("versionId").asText());
            }
        }
        configureAgent(agent -> agent.set("pluginVersionIds", json.valueToTree(List.of(bundle))));
        versions.revoke(enterprise, original, Instant.now());
        mvc.perform(get(base() + "/plugins/tool-sources/" + resource.id() + "/versions/" + original).cookie(cookie)).andExpect(status().is4xxClientError());
        write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().is4xxClientError());
    }

    @Test
    void repeatedIdenticalSourcesAreExposedOnceEvenWhenSavedEntriesExceedOneHundred() throws Exception {
        var choices = new LinkedHashMap<String, List<String>>();
        for (var source : directory()) {
            if (!source.path("code").asText().startsWith("collection_")) {
                var names = new ArrayList<String>();
                source.path("tools").forEach(tool -> names.add(tool.path("name").asText()));
                choices.put(source.path("code").asText(), names);
            }
        }
        var config = configuration(directory(), choices);
        var bundles = new ArrayList<String>();
        for (int index = 0; index < 6; index++) {
            bundles.add(publish(config));
        }
        assertTrue(pluginTools.list(enterprise, bundles.getFirst()).size() * bundles.size() > 100);
        configureAgent(agent -> agent.set("pluginVersionIds", json.valueToTree(bundles)));
        var iteration = new AtomicInteger();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (iteration.getAndIncrement() == 0) {
                assertEquals(config.path("tools").size(), pluginToolCount(request));
                String alias = null;
                for (var tool : request.path("tools")) {
                    if (tool.at("/function/name").asText().equals("platform_time")) {
                        alias = tool.at("/function/name").asText();
                    }
                }
                assertNotNull(alias);
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "one-call", "type", "function", "function", Map.of("name", alias, "arguments", "{}")))), "tool_calls");
            } else {
                frame(exchange, Map.of("content", "已查询当前时间。"), "stop");
            }
        };
        String run = submit();
        finish(run);
        assertEquals(1, calls.forRun(enterprise, run).size());
    }

    @Test
    void selectedToolMustBelongToItsSourceAndCannotOverrideConfirmationRules() throws Exception {
        var config = configuration(directory(), Map.of("todo_management", List.of("todo_create")));
        ((ObjectNode) config.path("tools").get(0)).put("operationClass", "read");
        write(base() + "/resources", Map.of("kind", "plugin", "name", "不可伪造确认", "description", "验证工具定义边界", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().is(422));
        ((ObjectNode) config.path("tools").get(0)).remove("operationClass");
        ((ObjectNode) config.path("tools").get(0)).put("name", "platform_time");
        String id = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "工具来源核对", "description", "验证实际工具所属来源", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "拒绝发布不存在的工具"), "1", UUID.randomUUID().toString()).andExpect(status().is(422));
    }

    private JsonNode directory() throws Exception {
        return data(mvc.perform(get(base() + "/plugins/tool-sources").cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode node(String id, String type, Map<String, Object> config) {
        return json.valueToTree(Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", config));
    }

    private ObjectNode configuration(JsonNode directory, Map<String, List<String>> choices) {
        var config = json.createObjectNode().put("icon", "NotebookPen").put("color", "mint").put("timeoutSeconds", 30);
        var sources = config.putArray("sources");
        var tools = config.putArray("tools");
        for (var source : directory) {
            if (choices.containsKey(source.path("code").asText())) {
                String id = source.path("resourceId").asText();
                sources.addObject().put("id", id).put("type", "builtin").put("versionId", source.path("versionId").asText());
                for (String name : choices.get(source.path("code").asText())) {
                    tools.addObject().put("id", UUID.randomUUID().toString()).put("sourceId", id).put("name", name);
                }
            }
        }
        assertEquals(choices.size(), sources.size());
        return config;
    }

    private String publish(ObjectNode config) throws Exception {
        var created = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "工作安排合集", "description", "按工具组合能力", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        assertFalse(created.path("draft").has("enabledToolNames"));
        schemas.validate("ResourceDetail", created);
        String id = created.at("/resource/id").asText();
        return data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "发布工具合集"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
    }

    private int pluginToolCount(JsonNode request) {
        var workspace = Set.of("read_file", "grep_files", "list_files", "write_file", "edit_file", "execute", "export_file");
        int count = 0;
        for (var tool : request.path("tools")) {
            if (!workspace.contains(tool.at("/function/name").asText())) {
                count++;
            }
        }
        return count;
    }

    private void model(List<PluginToolRecord> tools, Map<String, Object> arguments) {
        var iteration = new AtomicInteger();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (iteration.getAndIncrement() == 0) {
                assertEquals(tools.size(), pluginToolCount(request));
                var calls = new ArrayList<Map<String, Object>>();
                int index = 0;
                for (var tool : tools) {
                    String modelName = null;
                    for (var candidate : request.path("tools")) {
                        String name = candidate.at("/function/name").asText();
                        if (candidate.at("/function/description").asText().contains(tool.source().name()) && (name.equals(tool.definition().name()) || name.startsWith(tool.definition().name() + "_"))) {
                            modelName = name;
                        }
                    }
                    assertNotNull(modelName, "模型收到的工具必须可按原名称和来源识别");
                    assertFalse(modelName.matches("platform_[a-f0-9]{32}"));
                    if (tools.stream().filter(item -> item.definition().name().equals(tool.definition().name())).count() == 1) {
                        assertEquals(tool.definition().name(), modelName);
                    }
                    calls.add(Map.of("index", index++, "id", "call-" + tool.id(), "type", "function", "function", Map.of("name", modelName, "arguments", json.writeValueAsString(arguments))));
                }
                frame(exchange, Map.of("tool_calls", calls), "tool_calls");
            } else {
                frame(exchange, Map.of("content", "已完成所选工具调用。"), "stop");
            }
        };
    }

    private String submit() throws Exception {
        return data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn()).path("runId").asText();
    }

    private void finish(String run) throws Exception {
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertEquals("completed", state(run), () -> runs.find(enterprise, run, false).orElseThrow().toString());
    }

    private boolean terminal(String run) {
        return List.of("completed", "failed", "cancelled").contains(state(run));
    }

    private String state(String run) {
        return runs.find(enterprise, run, false).orElseThrow().status();
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var chunk = Map.of("id", "collection-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    @TestConfiguration
    static class ExtraSources {
        @Bean
        BuiltinPluginAdapter collectionAlpha(ResourceJson json) {
            return adapter("collection_alpha", "测试来源甲", json);
        }

        @Bean
        BuiltinPluginAdapter collectionBeta(ResourceJson json) {
            return adapter("collection_beta", "测试来源乙", json);
        }

        private BuiltinPluginAdapter adapter(String code, String name, ResourceJson json) {
            return new BuiltinPluginAdapter() {
                public String code() {
                    return code;
                }

                public String name() {
                    return name;
                }

                public String description() {
                    return "验证不同来源的同名工具。";
                }

                public List<ToolDefinition> tools() {
                    return List.of(new ToolDefinition("lookup", "查询当前来源。", "a".repeat(64), json.tree(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)), json.tree(Map.of("type", "object")), json.tree(Map.of("title", "查询来源")), "read", false, false, false, List.of(), 30));
                }

                public JsonNode call(String tool, String operation, JsonNode arguments, Duration timeout, ToolCallControl control) {
                    control.beforeSend();
                    return json.tree(Map.of("provider", code));
                }
            };
        }
    }
}
