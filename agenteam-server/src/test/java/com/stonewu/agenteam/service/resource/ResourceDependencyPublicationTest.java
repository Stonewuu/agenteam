package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventSqlMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.model.resource.request.DraftWriteRequest;
import com.stonewu.agenteam.model.resource.request.ResourceCreateRequest;
import com.stonewu.agenteam.model.resource.request.ResourcePublishRequest;
import com.stonewu.agenteam.model.resource.response.ResourceDetailView;
import com.stonewu.agenteam.model.resource.response.ResourceVersionView;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.execution.ExecutionConfigurationService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.ModelProfileFixture;
import com.stonewu.agenteam.support.ModelProfileTestData;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(SharedEnterpriseTestEdition.class)
class ResourceDependencyPublicationTest {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private AuthMapper users;

    @Autowired
    private EnterpriseProvisioningService enterprises;

    @Autowired
    private ModelProfileTestData models;

    @Autowired
    private ResourceDraftService drafts;

    @Autowired
    private ResourcePublishService publications;

    @Autowired
    private ResourceLifecycleService lifecycle;

    @Autowired
    private ResourceQueryService queries;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MybatisTestDatabase databaseAccess;

    private AuthContext actor;

    private String model;

    @Autowired
    private ExecutionConfigurationService executions;
    @Autowired
    private AgentHireMapper hires;
    @Autowired
    private PermissionMapper permissions;
    @Autowired
    private ResourceGrantService grants;
    @Autowired
    private UsableVersionService usable;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    static void close() throws Exception {
        ENVIRONMENT.close();
    }

    @BeforeEach
    void enterprise() {
        String id = UUID.randomUUID().toString();
        users.insertUser(id, "dependency-" + id, "unused-test-password", "依赖验收维护者", true, Instant.now());
        var user = users.findById(id).orElseThrow();
        String enterprise = enterprises.create("固定依赖验收", user, Instant.now()).enterpriseId();
        actor = new AuthContext(user, enterprise, Set.of());
        model = models.saveProfiles(List.of(new ModelProfileFixture(enterprise, id, "dependency-test", 1, "依赖测试模型", "openai", "test-model", "http://127.0.0.1:1/v1", null, new ModelCapabilities(true, true, 8192, 32768, List.of("text")), true)), ignored -> null).getFirst();
    }

    @Test
    void aNewVersionCannotReferBackToItsOwnResourceThroughAnEarlierVersion() throws Exception {
        var original = create("agent", "最初的智能体", agent());
        String agentId = original.resource().id();
        var first = publish(agentId);
        var flow = create("workflow", "引用已有智能体", flow(first.version().id()));
        var flowVersion = publish(flow.resource().id());
        var changed = agent();
        changed.withArray("workflowVersionIds").add(flowVersion.version().id());
        save(agentId, changed);
        int before = audits();
        assertEquals("RESOURCE_DEPENDENCY_CYCLE", assertThrows(ApiException.class, () -> publish(agentId)).code());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, agentId))));
        assertEquals(first.version().id(), queries.detail(actor, agentId).resource().publishedVersion().id());
        assertEquals(before, audits());
    }

    @Test
    void aWorkflowCannotHideAnotherWorkflowInsideAnAgentCapability() throws Exception {
        var inner = create("workflow", "内部工作流", flow(null));
        String innerVersion = publish(inner.resource().id()).version().id();
        var childConfig = agent();
        childConfig.withArray("workflowVersionIds").add(innerVersion);
        var child = create("agent", "有流程能力的智能体", childConfig);
        String childVersion = publish(child.resource().id()).version().id();
        var outer = create("workflow", "外部工作流", flow(childVersion));
        assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class, () -> publish(outer.resource().id())).code());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, outer.resource().id()))));
    }

    @Test
    void skillRequirementsMustBeDeclaredAndFixedKnowledgeDoesNotFollowNewPublications() throws Exception {
        var knowledge = create("knowledge", "业务资料", json.readTree("""
            {"icon":"Library","color":"blue","description":"业务资料","retrievalMode":"keyword","maxResults":5,"maxContextCharacters":5000}
            """));
        String knowledgeId = knowledge.resource().id();
        String firstKnowledge = publish(knowledgeId).version().id();
        var skillConfig = skill();
        skillConfig.withArray("knowledgeVersionIds").add(firstKnowledge);
        var skill = create("skill", "查阅资料", skillConfig);
        String skillVersion = publish(skill.resource().id()).version().id();
        var agentConfig = agent();
        agentConfig.withArray("skillVersionIds").add(skillVersion);
        var agent = create("agent", "需要补齐能力", agentConfig);
        String agentId = agent.resource().id();
        assertEquals("SKILL_DEPENDENCY_UNAVAILABLE", assertThrows(ApiException.class, () -> publish(agentId)).code());
        agentConfig.withArray("knowledgeVersionIds").add(firstKnowledge);
        save(agentId, agentConfig);
        var published = publish(agentId);
        String secondKnowledge = publish(knowledgeId).version().id();
        assertNotEquals(firstKnowledge, secondKnowledge);
        assertTrue(queries.version(actor, agentId, published.version().id()).dependencies().stream().anyMatch(value -> value.versionId().equals(firstKnowledge)));
        lifecycle.status(actor, knowledgeId, "disabled", Long.parseLong(queries.detail(actor, knowledgeId).resource().revision()));
        assertThrows(ApiException.class, () -> publish(agentId));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, agentId))));
        assertEquals(firstKnowledge, queries.version(actor, agentId, published.version().id()).config().get("knowledgeVersionIds") instanceof List<?> values ? values.getFirst() : null);
    }

    @Test
    void copiedWorkflowWithARevokedNodeCanBeSavedButNeedsANewChoiceBeforePublishing() throws Exception {
        var agent = create("agent", "节点智能体", agent());
        String agentId = agent.resource().id();
        var first = publish(agentId);
        var workflow = create("workflow", "待复制工作流", flow(first.version().id()));
        publish(workflow.resource().id());
        lifecycle.revoke(actor, agentId, first.version().id(), "更换节点配置", Long.parseLong(queries.detail(actor, agentId).resource().revision()));
        var copied = drafts.copy(actor, workflow.resource().id(), "工作流副本");
        var config = (ObjectNode) json.valueToTree(copied.draft());
        assertTrue(config.at("/nodes/1/config/agentVersionId").isNull());
        assertFalse(copied.fieldErrors().isEmpty());
        String copyId = copied.resource().id();
        save(copyId, config);
        assertThrows(ApiException.class, () -> publish(copyId));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, copyId))));
        String replacement = publish(agentId).version().id();
        ((ObjectNode) config.at("/nodes/1/config")).put("agentVersionId", replacement);
        save(copyId, config);
        assertEquals(replacement, publish(copyId).dependencies().getFirst().versionId());
    }

    @Test
    void publicationRejectsFutureNodeReferencesAndSchemasThatReadRemoteDefinitions() throws Exception {
        String dependency = publish(create("agent", "可引用智能体", agent()).resource().id()).version().id();
        var invalid = flow(dependency);
        ((ObjectNode) invalid.at("/nodes/1/config/inputMapping")).put("text", "${steps.end.output.result}");
        var workflow = create("workflow", "不能读取未来节点", invalid);
        assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class, () -> publish(workflow.resource().id())).code());
        var remote = flow(null);
        ((ObjectNode) remote.at("/nodes/0/config/inputSchema")).put("$ref", "https://schemas.example.test/input.json");
        var remoteFlow = create("workflow", "不能读取远程结构", remote);
        assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class, () -> publish(remoteFlow.resource().id())).code());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, remoteFlow.resource().id()))));
    }

    @Test
    void subagentsCanBeSelectedAndUsedWithoutHiringButStillNeedCurrentUsePermission() throws Exception {
        var childConfig = agent().put("instructions", "这个智能体自己的执行指令");
        String child = create("agent", "可调用的已有智能体", childConfig).resource().id();
        String childVersion = publish(child).version().id();
        var parentConfig = agent();
        parentConfig.putArray("subagentVersionIds").add(childVersion);
        String parent = create("agent", "调用已有智能体的员工", parentConfig).resource().id();
        String parentVersion = publish(parent).version().id();
        String userId = UUID.randomUUID().toString(), roleId = UUID.randomUUID().toString();
        users.insertUser(userId, "subagent-reader-" + userId, "unused-test-password", "子任务调用者", false, Instant.now());
        users.addMember(actor.enterpriseId(), userId, "子任务调用者", Instant.now());
        permissions.insertRole(roleId, actor.enterpriseId(), "subagent-test-" + roleId, "子任务调用测试", "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(actor.enterpriseId(), roleId, Set.of("capabilities.view", "agent.run", "agent.edit"));
        permissions.replaceUserRoles(userId, actor.enterpriseId(), Set.of(roleId), Instant.now());
        var reader = new AuthContext(users.findById(userId).orElseThrow(), actor.enterpriseId(), Set.of());
        grant(parent, reader.userId());
        hires.establish(actor.enterpriseId(), reader.userId(), parent, Instant.now());
        assertTrue(hires.forAgent(actor.enterpriseId(), reader.userId(), child, false).isEmpty());
        assertThrows(ApiException.class, () -> executions.normal(reader, parent, parentVersion));
        assertFalse(usable.list(reader, "agent", "", null, 50, null, true).items().stream().anyMatch(item -> item.resourceId().equals(child)));
        grant(child, reader.userId());
        assertTrue(usable.list(reader, "agent", "", null, 50, null, true).items().stream().anyMatch(item -> item.versionId().equals(childVersion)));
        var selected = executions.normal(reader, parent, parentVersion);
        assertEquals(childVersion, selected.snapshot().path("dependencies").get(0).path("versionId").asText());
        assertEquals("这个智能体自己的执行指令", selected.snapshot().path("dependencies").get(0).path("config").path("instructions").asText());
        assertTrue(hires.forAgent(actor.enterpriseId(), reader.userId(), child, false).isEmpty(), "引用与运行不能创建子智能体雇佣关系");
        childConfig.put("instructions", "较新版本的指令");
        save(child, childConfig);
        publish(child);
        assertEquals(childVersion, executions.normal(reader, parent, parentVersion).snapshot().path("dependencies").get(0).path("versionId").asText());
        grants.replace(actor, child, Long.parseLong(queries.detail(actor, child).resource().revision()), List.of());
        assertThrows(ApiException.class, () -> executions.normal(reader, parent, parentVersion));
        grant(child, reader.userId());
        lifecycle.revoke(actor, child, childVersion, "停止使用此版本", Long.parseLong(queries.detail(actor, child).resource().revision()));
        assertThrows(ApiException.class, () -> executions.normal(reader, parent, parentVersion));
    }

    @Test
    void subagentReferencesRejectSelfCyclesIndirectCyclesAndOtherEnterprises() throws Exception {
        String parent = create("agent", "父智能体", agent()).resource().id();
        String first = publish(parent).version().id();
        var self = agent();
        self.putArray("subagentVersionIds").add(first);
        save(parent, self);
        assertEquals("RESOURCE_DEPENDENCY_CYCLE", assertThrows(ApiException.class, () -> publish(parent)).code());
        String child = create("agent", "间接引用父智能体", self).resource().id();
        String childVersion = publish(child).version().id();
        var indirect = agent();
        indirect.putArray("subagentVersionIds").add(childVersion);
        save(parent, indirect);
        assertEquals("RESOURCE_DEPENDENCY_CYCLE", assertThrows(ApiException.class, () -> publish(parent)).code());
        var originalActor = actor;
        String originalModel = model;
        enterprise();
        String foreign = publish(create("agent", "其他企业的智能体", agent()).resource().id()).version().id();
        actor = originalActor;
        model = originalModel;
        var cross = agent();
        cross.putArray("subagentVersionIds").add(foreign);
        save(parent, cross);
        assertEquals("RESOURCE_DEPENDENCY_UNAVAILABLE", assertThrows(ApiException.class, () -> publish(parent)).code());
    }

    @Test
    void unsupportedWorkflowCapabilitiesAreNotOfferedAsSubagents() throws Exception {
        String workflow = publish(create("workflow", "已有流程", flow(null)).resource().id()).version().id();
        var config = agent();
        config.withArray("workflowVersionIds").add(workflow);
        String child = create("agent", "带工作流能力的员工", config).resource().id();
        String version = publish(child).version().id();
        assertFalse(usable.list(actor, "agent", "", null, 50, null, true).items().stream().anyMatch(item -> item.resourceId().equals(child)));
        var parent = agent();
        parent.putArray("subagentVersionIds").add(version);
        String id = create("agent", "不能调用此子智能体", parent).resource().id();
        assertThrows(ApiException.class, () -> publish(id));
    }

    @Test
    void latestSubagentsArePagedAsResourcesAndHistoricalVersionsStaySelectable() throws Exception {
        String agent = create("agent", "分页智能体", agent()).resource().id();
        String first = publish(agent).version().id();
        String second = publish(agent).version().id();
        String third = publish(agent).version().id();
        String other = create("agent", "另一个分页智能体", agent()).resource().id();
        String otherVersion = publish(other).version().id();
        var page = usable.list(actor, "agent", "分页智能体", null, 1, null, true);
        var next = usable.list(actor, "agent", "分页智能体", page.nextCursor(), 1, null, true);
        assertTrue(page.hasMore());
        assertFalse(next.hasMore());
        assertEquals(Set.of(third, otherVersion), Set.of(page.items().getFirst().versionId(), next.items().getFirst().versionId()));
        var history = usable.list(actor, "agent", "", null, 1, null, true, agent);
        assertEquals(third, history.items().getFirst().versionId());
        var earlier = usable.list(actor, "agent", "", history.nextCursor(), 1, null, true, agent);
        assertEquals(second, earlier.items().getFirst().versionId());
        assertEquals(first, usable.list(actor, "agent", "", earlier.nextCursor(), 1, null, true, agent).items().getFirst().versionId());
        assertThrows(ApiException.class, () -> usable.list(actor, "agent", "", history.nextCursor(), 1, null, true, other));
        assertEquals(first, usable.list(actor, "agent", null, null, null, List.of(first), false).items().getFirst().versionId());
        lifecycle.revoke(actor, agent, third, "不再使用此版本", Long.parseLong(queries.detail(actor, agent).resource().revision()));
        assertTrue(usable.list(actor, "agent", "分页智能体", null, 20, null, true).items().stream().anyMatch(item -> item.versionId().equals(second)));
    }

    @Test
    void savingAnAgentRemovesBothObsoleteAssistantFieldsFromTheStoredDraft() throws Exception {
        var config = agent().put("researchSubagentEnabled", true);
        config.putArray("subagents").addObject().put("name", "不再使用的助手").put("instructions", "旧指令");
        String id = create("agent", "清理旧配置", config).resource().id();
        assertFalse(queries.detail(actor, id).draft().containsKey("researchSubagentEnabled"));
        assertFalse(queries.detail(actor, id).draft().containsKey("subagents"));
        databaseAccess.mapper(ResourceDraftTableMapper.class).update(new LambdaUpdateWrapper<ResourceDraftRow>()
            .eq(ResourceDraftRow::getEnterpriseId, actor.enterpriseId()).eq(ResourceDraftRow::getResourceId, id)
            .set(ResourceDraftRow::getConfigJson, json.writeValueAsString(config)).set(ResourceDraftRow::getConfigHash, new ResourceJson(json).hash(config)));
        assertTrue(queries.detail(actor, id).draft().containsKey("subagents"), "构造已经保存旧字段的草稿，验证再次保存确实清除数据库内容");
        save(id, config);
        assertFalse(queries.detail(actor, id).draft().containsKey("researchSubagentEnabled"));
        assertFalse(queries.detail(actor, id).draft().containsKey("subagents"));
        var saved = publish(id);
        assertFalse(saved.config().containsKey("researchSubagentEnabled"));
        assertFalse(saved.config().containsKey("subagents"));
    }

    private void grant(String resource, String user) {
        grants.replace(actor, resource, Long.parseLong(queries.detail(actor, resource).resource().revision()), List.of(new ResourceGrantSpec("user", user, "use")));
    }

    private ResourceDetailView create(String kind, String name, Object config) {
        return drafts.create(actor, new ResourceCreateRequest(kind, name, "固定依赖行为验证", List.of(), object(config)));
    }

    private ResourceVersionView publish(String id) {
        long revision = Long.parseLong(queries.detail(actor, id).resource().revision());
        return publications.publish(actor, id, new ResourcePublishRequest("固定依赖验收版本", null, null), revision);
    }

    private void save(String id, Object config) {
        var current = queries.detail(actor, id).resource();
        drafts.save(actor, id, Long.parseLong(current.revision()), new DraftWriteRequest(current.name(), current.description(), List.of(), object(config)));
    }

    private Map<String, Object> object(Object value) {
        return json.convertValue(value, new TypeReference<>() {
        });
    }

    private int audits() {
        return Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, actor.enterpriseId())));
    }

    private ObjectNode agent() throws Exception {
        return ((ObjectNode) json.readTree("""
            {"icon":"Sparkles","color":"purple","agentType":"chat","businessRole":"资料整理","modelProfileId":null,
             "instructions":"整理已提供的资料。","maxSteps":20,"timeoutSeconds":120,
             "attachmentsEnabled":false,"welcomeMessage":"","suggestedQuestions":[],"skillVersionIds":[],"pluginVersionIds":[],
             "knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],"entryWorkflowVersionId":null,"historyMessageLimit":20,
             "memoryEnabled":false,"memoryFields":[],"businessTerms":[],"researchSubagentEnabled":false,"publicExamples":[]}
            """)).put("modelProfileId", model);
    }

    private ObjectNode skill() throws Exception {
        return (ObjectNode) json.readTree("""
            {"icon":"BookOpen","color":"purple","scenario":"","inputDescription":"","instructions":"查阅资料并说明依据。",
             "outputDescription":"","example":"","pluginVersionIds":[],"knowledgeVersionIds":[],"showInWorkspace":false}
            """);
    }

    private ObjectNode flow(String agentVersion) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "start", Map.of("inputSchema", Map.of("type", "object"))));
        if (agentVersion != null) {
            nodes.add(node("work", "agent", Map.of("agentVersionId", agentVersion, "inputMapping", Map.of("text", "${input.text}"))));
        }
        nodes.add(node("end", "end", Map.of("outputMapping", Map.of("result", "${input.text}"))));
        List<Map<String, String>> edges = new ArrayList<>();
        for (int index = 1; index < nodes.size(); index++) {
            edges.add(Map.of("edgeId", "edge" + index, "source", nodes.get(index - 1).get("nodeId").toString(), "target", nodes.get(index).get("nodeId").toString(), "branch", "default"));
        }
        return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", nodes, "edges", edges));
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        return Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 60, "failurePolicy", "stop", "config", config);
    }
}
