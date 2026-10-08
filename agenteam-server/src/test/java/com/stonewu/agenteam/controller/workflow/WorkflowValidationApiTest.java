package com.stonewu.agenteam.controller.workflow;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.plugin.PluginToolSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.service.workflow.WorkflowDependencyOptionsService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 正式接口读取真实版本和授权，校验不保存草稿、不创建执行，也不调用模型。
 */
@Import(SharedEnterpriseTestEdition.class)
class WorkflowValidationApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private WorkflowDependencyOptionsService dependencyOptions;

    @Autowired
    private ResourceGrantService grants;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void validatesUnsavedContentWithoutChangingTheDraftOrStartingAnyWork() throws Exception {
        var graph = graph("agent", Map.of("agentVersionId", version, "inputMapping", Map.of("text", "${input.text}")));
        String flow = create("workflow", graph);
        var before = detail(flow);
        ((ObjectNode) graph.at("/nodes/1/config/inputMapping")).put("text", "补充要求：${input.text}");
        var result = data(mvc.perform(post(path(flow)).cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(graph))).andExpect(status().isOk()).andReturn());
        schemas.validate("WorkflowValidation", result);
        assertTrue(result.path("valid").asBoolean());
        assertTrue(result.path("errors").isEmpty());
        assertEquals(before, detail(flow));
        assertEquals(0, count("agent_run"));
        assertEquals(0, count("tool_call"));
        assertEquals(0, reserved());
    }

    @Test
    void invalidNodeIsLocatedAndTheSameGraphCannotBePublished() throws Exception {
        var graph = graph("transform", Map.of("fields", List.of(Map.of("target", "answer", "template", "${steps.end.output.answer}"))));
        String flow = create("workflow", graph);
        int versionsBefore = count("resource_version");
        var result = validate(flow, graph);
        assertFalse(result.path("valid").asBoolean());
        assertEquals("work", result.at("/errors/0/nodeId").asText());
        schemas.validate("WorkflowValidation", result);
        change(HttpMethod.POST, base() + "/resources/" + flow + "/publish", Map.of("releaseNote", "不能发布未来引用"), "1", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertEquals(versionsBefore, count("resource_version"));
        assertEquals(0, count("agent_run"));
    }

    @Test
    void aSkillMustBelongToTheSelectedFixedAgentVersion() throws Exception {
        String skill = create("skill", Map.of("icon", "BookOpen", "color", "blue", "scenario", "", "inputDescription", "", "instructions", "整理已有内容。", "outputDescription", "", "example", "", "pluginVersionIds", List.of(), "knowledgeVersionIds", List.of(), "showInWorkspace", false));
        String skillVersion = publish(skill);
        var graph = graph("skill", Map.of("agentVersionId", version, "skillVersionId", skillVersion, "inputMapping", Map.of("text", "${input.text}")));
        String flow = create("workflow", graph);
        var invalid = validate(flow, graph);
        assertFalse(invalid.path("valid").asBoolean());
        assertEquals("work", invalid.at("/errors/0/nodeId").asText());
        configureAgent(config -> config.withArray("skillVersionIds").add(skillVersion));
        ((ObjectNode) graph.at("/nodes/1/config")).put("agentVersionId", version);
        assertTrue(validate(flow, graph).path("valid").asBoolean());
        change(HttpMethod.PUT, base() + "/resources/" + flow + "/draft", Map.of("name", "工作流验收", "description", "验证实际依赖", "tagIds", List.of(), "config", graph), "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertFalse(publish(flow).isBlank());
    }

    @Test
    void toolEntriesMustExistInThePublishedPluginAndReflectCurrentDisablement() throws Exception {
        var pluginConfig = json.readTree("""
            {"icon":"Box","color":"blue","pluginType":"builtin","builtinCode":"web_read","transport":null,
             "endpoint":null,"credentialId":null,"timeoutSeconds":30,"enabledToolNames":["read_url"]}
            """);
        String pluginVersion = publish(create("plugin", pluginConfig));
        var options = data(mvc.perform(get(base() + "/workflows/dependency-options/" + pluginVersion).cookie(cookie)).andExpect(status().isOk()).andReturn());
        String toolId = options.at("/tools/0/entryId").asText();
        assertFalse(toolId.isBlank());
        var graph = graph("tool", Map.of("pluginVersionId", pluginVersion, "toolId", "missing_tool", "inputMapping", Map.of("url", "https://invalid.example/test")));
        String flow = create("workflow", graph);
        assertFalse(validate(flow, graph).path("valid").asBoolean());
        ((ObjectNode) graph.at("/nodes/1/config")).put("toolId", toolId);
        assertTrue(validate(flow, graph).path("valid").asBoolean());
        databaseAccess.mapper(PluginToolSqlMapper.class).update(new LambdaUpdateWrapper<PluginToolRow>().eq(PluginToolRow::getEnterpriseId, (enterprise)).eq(PluginToolRow::getPluginVersionId, (pluginVersion)).set(PluginToolRow::getEnabled, false));
        assertFalse(validate(flow, graph).path("valid").asBoolean());
        assertEquals(0, count("tool_call"));
    }

    @Test
    void enterpriseResourceKindAndRequestForgeryChecksRemainEffective() throws Exception {
        var graph = graph("transform", Map.of("fields", List.of(Map.of("target", "answer", "literal", "已有内容"))));
        String flow = create("workflow", graph);
        String other = provisioning.create("另一工作流企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        write("/api/v1/enterprises/" + other + "/workflows/" + flow + "/validate", graph, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        write(path(agent), graph, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        mvc.perform(post(path(flow)).cookie(cookie).header("Origin", "http://localhost:3000").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(graph))).andExpect(status().isForbidden());
    }

    @Test
    void nodeOptionsOnlyExposeTheSelectedVersionsPublicCapabilitiesAndRejectRevocation() throws Exception {
        var options = data(mvc.perform(get(base() + "/workflows/dependency-options/" + version).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("WorkflowDependencyOptions", options);
        assertEquals("chat", options.path("agentType").asText());
        assertFalse(options.has("instructions"));
        assertFalse(options.has("config"));
        assertTrue(options.path("skills").isEmpty());
        var pluginConfig = json.readTree("""
            {"icon":"Box","color":"blue","pluginType":"builtin","builtinCode":"web_read","transport":null,
             "endpoint":null,"credentialId":null,"timeoutSeconds":30,"enabledToolNames":["read_url"]}
            """);
        String pluginVersion = publish(create("plugin", pluginConfig));
        String path = base() + "/workflows/dependency-options/" + pluginVersion;
        var plugin = data(mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("WorkflowDependencyOptions", plugin);
        assertEquals("read_url", plugin.at("/tools/0/name").asText());
        assertTrue(plugin.at("/tools/0/inputSchema/properties/url").isObject());
        assertFalse(plugin.has("endpoint"));
        assertFalse(plugin.has("credentialId"));
        databaseAccess.mapper(PluginToolSqlMapper.class).update(new LambdaUpdateWrapper<PluginToolRow>().eq(PluginToolRow::getEnterpriseId, (enterprise)).eq(PluginToolRow::getPluginVersionId, (pluginVersion)).set(PluginToolRow::getEnabled, false));
        assertTrue(data(mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("tools").isEmpty());
        databaseAccess.mapper(ResourceVersionSqlMapper.class).update(new LambdaUpdateWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getId, (pluginVersion)).set(ResourceVersionRow::getStatus, "revoked"));
        mvc.perform(get(path).cookie(cookie)).andExpect(status().isNotFound());
        String other = provisioning.create("另一选项企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        mvc.perform(get("/api/v1/enterprises/" + other + "/workflows/dependency-options/" + version).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void aWorkflowAuthorWithUseOnlyAccessCanChooseNodesButCannotReadPrivateConfiguration() {
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "workflow-author", "工作流作者", "只授予配置和使用能力", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("capabilities.view", "workflow.edit", "agent.run"));
        var user = EnterpriseTestData.member(users, permissions, enterprise, "workflow-author-" + UUID.randomUUID(), "节点选择所用的独立口令", "工作流作者", List.of(role));
        var actor = new AuthContext(user, enterprise, Set.copyOf(permissions.listPermissionCodes(user.id(), enterprise)));
        var owner = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        long revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getRevision).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getRevision()).toList());
        grants.replace(owner, agent, revision, List.of(new ResourceGrantSpec("user", user.id(), "use")));
        assertFalse(actor.permissions().contains("agent.view"));
        assertFalse(actor.permissions().contains("agent.edit"));
        var choices = dependencyOptions.options(actor, version);
        assertEquals("chat", choices.agentType());
        assertFalse(json.valueToTree(choices).has("instructions"));
        grants.replace(owner, agent, revision + 1, List.of());
        assertEquals(404, assertThrows(ApiException.class, () -> dependencyOptions.options(actor, version)).getStatusCode().value());
    }

    private ObjectNode graph(String type, Object settings) {
        return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", List.of(node("start", "start", Map.of("inputSchema", Map.of("type", "object"))), node("work", type, settings), node("end", "end", Map.of("outputMapping", Map.of("text", "完成")))), "edges", List.of(Map.of("edgeId", "first", "source", "start", "target", "work", "branch", "default"), Map.of("edgeId", "last", "source", "work", "target", "end", "branch", "default"))));
    }

    private Map<String, Object> node(String id, String type, Object config) {
        return Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 60, "failurePolicy", "stop", "config", config);
    }

    private String create(String kind, Object config) throws Exception {
        return data(write(base() + "/resources", Map.of("kind", kind, "name", "工作流验收", "description", "验证实际依赖", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    private String publish(String resource) throws Exception {
        return data(change(HttpMethod.POST, base() + "/resources/" + resource + "/publish", Map.of("releaseNote", "工作流依赖验收"), detail(resource).at("/resource/revision").asText(), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
    }

    private JsonNode detail(String id) throws Exception {
        return data(mvc.perform(get(base() + "/resources/" + id).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode validate(String resource, JsonNode graph) throws Exception {
        return data(write(path(resource), graph, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
    }

    private String path(String id) {
        return base() + "/workflows/" + id + "/validate";
    }
}
