package com.stonewu.agenteam.controller.resource;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.execution.ExecutionConfigurationService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceLifecycleService;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SharedEnterpriseTestEdition.class)
class ResourceDeletionExecutionTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private ResourceMapper resources;
    @Autowired
    private ResourceVersionMapper versions;
    @Autowired
    private ResourceLifecycleService resourceLifecycle;
    @Autowired
    private ExecutionConfigurationService configurations;
    @Autowired
    private ExecutionToolCatalog catalog;
    @Autowired
    private ScheduleManagementService plans;
    @Autowired
    private ScheduleMapper schedules;
    @Autowired
    private RunMapper runs;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void currentAndHistoricalReferencesAllowDeletionAndKeepThePublishedContent(boolean currentStillUsesPlugin) throws Exception {
        String plugin = plugin();
        String pluginVersion = publish(plugin);
        configureAgent(config -> config.withArray("pluginVersionIds").add(pluginVersion));
        String first = version;
        configureAgent(config -> {
            if (!currentStillUsesPlugin) {
                config.putArray("pluginVersionIds");
            }
        });
        String latest = version;
        var beforeFirst = versions.find(enterprise, first).orElseThrow();
        var beforeLatest = versions.find(enterprise, latest).orElseThrow();
        var saved = run(configurations.normal(actor(), agent, first).snapshot());
        var oldTool = catalog.list(saved).values().stream().filter(value -> value.resourceId().equals(plugin)).findFirst().orElseThrow();
        assertTrue(resourceLifecycle.impact(actor(), plugin).canDelete());

        delete(plugin);

        assertEquals(beforeFirst, versions.find(enterprise, first).orElseThrow());
        assertEquals(beforeLatest, versions.find(enterprise, latest).orElseThrow());
        assertTrue(configurations.normal(actor(), agent, first).snapshot().at("/config/pluginVersionIds").isEmpty());
        assertTrue(configurations.normal(actor(), agent, latest).snapshot().at("/config/pluginVersionIds").isEmpty());
        assertFalse(catalog.list(saved).containsKey(oldTool.alias()), "旧执行配置也不能重新暴露已删除工具");
        assertThrows(ApiException.class, () -> catalog.requireCurrent(saved, oldTool));
        assertEquals("deleted", resources.find(enterprise, plugin, true, false).orElseThrow().status());

        var restored = resourceLifecycle.restore(actor(), plugin, resources.find(enterprise, plugin, true, false).orElseThrow().revision());
        assertEquals("disabled", restored.status());
        assertThrows(ApiException.class, () -> configurations.normal(actor(), agent, first));
        resourceLifecycle.status(actor(), plugin, "active", Long.parseLong(restored.revision()));
        assertEquals(pluginVersion, configurations.normal(actor(), agent, first).snapshot().at("/config/pluginVersionIds/0").asText());
    }

    @Test
    void deletedDependenciesRemoveSkillsAndOptionalWorkflowsButNeverSkipRequiredNodes() throws Exception {
        String plugin = plugin();
        String pluginVersion = publish(plugin);
        var skill = json.readTree("""
            {"icon":"BookOpen","color":"purple","scenario":"","inputDescription":"","instructions":"读取网页后整理内容。",
             "outputDescription":"","example":"","pluginVersionIds":[],"knowledgeVersionIds":[],"showInWorkspace":false}
            """);
        ((ObjectNode) skill).withArray("pluginVersionIds").add(pluginVersion);
        String skillVersion = publish(create("skill", skill));
        String child = create("agent", resources.find(enterprise, agent, false, false).orElseThrow().config());
        String childVersion = publish(child);
        var flow = flow(childVersion);
        String workflow = create("workflow", flow);
        String workflowVersion = publish(workflow);
        configureAgent(config -> {
            config.withArray("pluginVersionIds").add(pluginVersion);
            config.withArray("skillVersionIds").add(skillVersion);
            config.withArray("workflowVersionIds").add(workflowVersion);
            config.withArray("subagentVersionIds").add(childVersion);
        });

        delete(plugin);
        var withoutPlugin = configurations.normal(actor(), agent, version).snapshot();
        assertTrue(withoutPlugin.at("/config/pluginVersionIds").isEmpty());
        assertTrue(withoutPlugin.at("/config/skillVersionIds").isEmpty());
        assertEquals(workflowVersion, withoutPlugin.at("/config/workflowVersionIds/0").asText());

        delete(child);
        var withoutChild = configurations.normal(actor(), agent, version).snapshot();
        assertTrue(withoutChild.at("/config/subagentVersionIds").isEmpty());
        assertTrue(withoutChild.at("/config/workflowVersionIds").isEmpty());
        var transaction = new TransactionTemplate(transactionManager);
        assertThrows(ApiException.class, () -> transaction.execute(status -> configurations.workflowPreview(actor(), workflow, flow)));
        assertEquals(3, versions.find(enterprise, workflowVersion).orElseThrow().config().path("nodes").size());

        ObjectNode entry = resources.find(enterprise, agent, false, false).orElseThrow().config().deepCopy();
        entry.put("agentType", "workflow").putNull("modelProfileId").put("entryWorkflowVersionId", workflowVersion);
        entry.remove("temperature");
        entry.putArray("subagentVersionIds");
        assertEquals("RESOURCE_DEPENDENCY_UNAVAILABLE", assertThrows(ApiException.class,
            () -> transaction.execute(status -> configurations.preview(actor(), agent, entry))).code());
    }

    @Test
    void deletionPausesAffectedPlansAndLeavesOtherFixedVersionsAlone() throws Exception {
        String originalVersion = version;
        String plugin = plugin();
        String pluginVersion = publish(plugin);
        configureAgent(config -> config.withArray("pluginVersionIds").add(pluginVersion));
        String hire = hires.forAgent(enterprise, admin, agent, false).orElseThrow().id();
        var first = plans.create(actor(), plan(hire, version, true));
        var second = plans.create(actor(), plan(hire, version, true));
        var paused = plans.create(actor(), plan(hire, version, false));
        var unaffected = plans.create(actor(), plan(hire, originalVersion, true));
        var impact = resourceLifecycle.impact(actor(), plugin);
        assertTrue(impact.canDelete());
        assertEquals(2, impact.enabledScheduleCount());

        delete(plugin);

        for (String id : List.of(first.id(), second.id())) {
            var value = schedules.find(enterprise, admin, id, false, false).orElseThrow();
            assertFalse(value.enabled());
            assertNull(value.nextRunAt());
            assertEquals("SCHEDULE_RESOURCE_UNAVAILABLE", value.pauseReason());
        }
        assertNull(schedules.find(enterprise, admin, paused.id(), false, false).orElseThrow().pauseReason());
        assertTrue(schedules.find(enterprise, admin, unaffected.id(), false, false).orElseThrow().enabled());
        var restored = resourceLifecycle.restore(actor(), plugin, resources.find(enterprise, plugin, true, false).orElseThrow().revision());
        resourceLifecycle.status(actor(), plugin, "active", Long.parseLong(restored.revision()));
        assertFalse(schedules.find(enterprise, admin, first.id(), false, false).orElseThrow().enabled());
    }

    @Test
    void modelRequestsKeepOtherToolsAndDoNotIncludeDeletedTools() throws Exception {
        String deleted = plugin();
        String retained = plugin("platform_basics", "platform_time");
        String deletedVersion = publish(deleted);
        String retainedVersion = publish(retained);
        configureAgent(config -> config.withArray("pluginVersionIds").add(deletedVersion).add(retainedVersion));
        var before = catalog.list(run(configurations.normal(actor(), agent, version).snapshot()));
        String deletedName = before.values().stream().filter(value -> value.resourceId().equals(deleted)).findFirst().orElseThrow().definition().name();
        String retainedName = before.values().stream().filter(value -> value.resourceId().equals(retained)).findFirst().orElseThrow().definition().name();
        delete(deleted);
        var observed = new AtomicReference<JsonNode>();
        modelResponse = exchange -> {
            observed.set(json.readTree(exchange.getRequestBody()));
            var chunk = Map.of("id", "resource-deletion", "object", "chat.completion.chunk", "created", 1, "model", "test-model",
                "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", "已完成。"), "finish_reason", "stop")));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        allowModelCalls = true;
        String id = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn()).path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, id, false).orElseThrow().terminal());
        }
        assertEquals("completed", runs.find(enterprise, id, false).orElseThrow().status());
        assertNotNull(observed.get());
        Set<String> names = new HashSet<>();
        for (var tool : observed.get().path("tools")) {
            names.add(tool.at("/function/name").asText());
        }
        assertFalse(names.contains(deletedName));
        assertTrue(names.contains(retainedName));
    }

    private AuthContext actor() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }

    private String plugin() throws Exception {
        return plugin("web_read", "read_url");
    }

    private String plugin(String builtin, String name) throws Exception {
        ObjectNode config = (ObjectNode) json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"builtin","builtinCode":"web_read","transport":null,
             "endpoint":null,"credentialId":null,"timeoutSeconds":30,"enabledToolNames":["read_url"]}
            """);
        config.put("builtinCode", builtin);
        config.putArray("enabledToolNames").add(name);
        return create("plugin", config);
    }

    private String create(String kind, JsonNode config) throws Exception {
        return data(write(base() + "/resources", Map.of("kind", kind, "name", "删除验证-" + UUID.randomUUID(),
            "description", "验证删除后的实际可用能力", "tagIds", List.of(), "config", config), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    private String publish(String id) throws Exception {
        return data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "删除行为验证"),
            Long.toString(resources.find(enterprise, id, false, false).orElseThrow().revision()), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
    }

    private void delete(String id) throws Exception {
        change(HttpMethod.DELETE, base() + "/resources/" + id, null,
            Long.toString(resources.find(enterprise, id, false, false).orElseThrow().revision()), UUID.randomUUID().toString())
            .andExpect(status().isOk());
    }

    private RunRecord run(JsonNode config) {
        var value = mock(RunRecord.class);
        when(value.enterpriseId()).thenReturn(enterprise);
        when(value.agentVersionId()).thenReturn(version);
        when(value.executionConfig()).thenReturn(config);
        when(value.mode()).thenReturn("interactive");
        return value;
    }

    private ScheduleWriteRequest plan(String hire, String fixedVersion, boolean enabled) {
        return new ScheduleWriteRequest("资源删除验证", hire, fixedVersion, "读取网页后整理内容", "daily", null,
            "09:00", List.of(), null, "UTC", enabled, 0);
    }

    private ObjectNode flow(String childVersion) {
        return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", List.of(
            node("start", "start", Map.of("inputSchema", Map.of("type", "object"))),
            node("work", "agent", Map.of("agentVersionId", childVersion, "inputMapping", Map.of("text", "处理资料"))),
            node("end", "end", Map.of("outputMapping", Map.of("text", "完成")))), "edges", List.of(
            Map.of("edgeId", "first", "source", "start", "target", "work", "branch", "default"),
            Map.of("edgeId", "second", "source", "work", "target", "end", "branch", "default"))));
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        return Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0),
            "timeoutSeconds", 60, "failurePolicy", "stop", "config", config);
    }
}
