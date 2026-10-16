package com.stonewu.agenteam.controller.plugin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.plugin.JavaPluginInstallationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.plugin.entity.JavaPluginInstallationRow;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.plugin.JavaPluginRegistry;
import com.stonewu.agenteam.service.resource.ExecutionDependencyService;
import com.stonewu.agenteam.service.plugin.PluginDiscoveryService;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.provider.JavaPluginToolProvider;
import com.stonewu.agenteam.service.tool.provider.ToolProviderContext;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.JavaPluginFixture;
import com.stonewu.agenteam.support.ApiContractAssertions;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.tool.api.ToolSource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实数据库、上传接口及独立 Java 进程共同验证安装和旧版本失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SharedEnterpriseTestEdition.class)
class JavaPluginApiTest {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AuthMapper users;
    @Autowired private PermissionMapper permissions;
    @Autowired private EnterpriseProvisioningService enterprises;
    @Autowired private JavaPluginInstallationMapper installations;
    @Autowired private JavaPluginRegistry registry;
    @Autowired private JavaPluginToolProvider provider;
    @Autowired private PluginToolMapper publishedTools;
    @Autowired private ExecutionToolCatalog catalog;
    @Autowired private ResourceMapper resources;
    @Autowired private ExecutionDependencyService dependencies;
    @Autowired private PluginDiscoveryService discovery;
    private Session session;
    private String user;
    private String enterprise;
    private byte[] firstPackage;
    private byte[] secondPackage;

    private record Session(Cookie cookie, String token) {
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry properties) {
        ENVIRONMENT.properties(properties);
    }

    @BeforeAll
    void bootstrap() throws Exception {
        firstPackage = JavaPluginFixture.build("1.0.0");
        secondPackage = JavaPluginFixture.build("1.1.0");
        var response = write(HttpMethod.POST, "/api/v1/auth/bootstrap", csrf(null), Map.of(
            "setupCredential", "isolated-invitation-setup-credential", "username", "java-plugin-admin",
            "displayName", "Java 插件管理员", "password", "Java插件独立测试口令2026!", "enterpriseName", "插件基础企业",
            "email", "java-plugin@example.test", "timezone", "Asia/Shanghai"), null)
            .andExpect(status().isCreated()).andReturn();
        user = data(response).path("id").asText();
        session = csrf(response.getResponse().getCookie("SESSION"));
    }

    @BeforeEach
    void prepare() {
        enterprise = enterprises.create("Java 插件测试企业", users.findById(user).orElseThrow(), Instant.now()).enterpriseId();
    }

    @AfterAll
    void close() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void uploadPublishInvokeUpgradeAndUninstallPreserveOldReferencesWithoutExecutingThem() throws Exception {
        String old = upload(firstPackage);
        activate(old, null).andExpect(status().isOk());
        mvc.perform(put(packages() + "/content").cookie(session.cookie()).header("Origin", "http://localhost:3000")
            .header("X-CSRF-Token", session.token()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_OCTET_STREAM).content("不是插件包"))
            .andExpect(status().isUnprocessableEntity());
        String duplicate = upload(firstPackage);
        activate(duplicate, old).andExpect(status().isConflict());
        String next = upload(secondPackage);
        activate(next, null).andExpect(status().isConflict());
        assertEquals(old, registry.current(enterprise, "example").getId());
        assertNotNull(raw(old).getArtifact());

        var configuration = json.readTree("""
            {"icon":"Box","color":"purple","timeoutSeconds":20,"sources":[{"id":"example","type":"java",
            "versionId":"placeholder","credentialId":null,"configuration":{"prefix":"实例配置"}}],
            "tools":[{"id":"echo-selection","sourceId":"example","name":"echo"}]}
            """);
        ((ObjectNode) configuration.path("sources").get(0)).put("versionId", old);
        String resource = data(write(HttpMethod.POST, base() + "/resources", session, Map.of("kind", "plugin",
            "name", "Java 业务插件", "description", "验证 Java 工具", "tagIds", List.of(), "config", configuration), null)
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String version = data(write(HttpMethod.POST, base() + "/resources/" + resource + "/publish", session,
            Map.of("releaseNote", "验证 Java 插件"), "1").andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        assertEquals(1, publishedTools.list(enterprise, version).size());
        assertEquals("echo", discovery.discover(enterprise, configuration).getFirst().name());
        var agentConfig = (ObjectNode) json.readTree("""
            {"icon":"Sparkles","color":"purple","agentType":"chat","businessRole":"资料整理","modelProfileId":"input-options-model",
             "instructions":"整理资料","maxSteps":20,"timeoutSeconds":120,"attachmentsEnabled":false,
             "welcomeMessage":"","suggestedQuestions":[],"skillVersionIds":[],"pluginVersionIds":[],
             "knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],"entryWorkflowVersionId":null,
             "historyMessageLimit":20,"memoryEnabled":false,"memoryFields":[],"businessTerms":[],"publicExamples":[]}
            """);
        agentConfig.putArray("pluginVersionIds").add(version);
        String agent = data(write(HttpMethod.POST, base() + "/resources", session, Map.of("kind", "agent",
            "name", "扩展插件调用员", "description", "验证未启动的智能体定义", "tagIds", List.of(), "config", agentConfig), null)
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        var actor = new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.of());
        var definition = resources.find(enterprise, agent, false, false).orElseThrow();
        assertEquals(1, dependencies.prepareInputOptions(actor, definition).resource().config().path("pluginVersionIds").size());
        var option = data(mvc.perform(get(base() + "/resources/usable-versions").param("kind", "plugin")
            .param("versionIds", version).cookie(session.cookie())).andExpect(status().isOk()).andReturn())
            .at("/items/0");
        new ApiContractAssertions().validate("UsableVersion", option);
        assertEquals(json.valueToTree(List.of("java")), option.path("pluginTypes"));
        var run = mock(RunRecord.class);
        when(run.enterpriseId()).thenReturn(enterprise);
        when(run.mode()).thenReturn("interactive");
        when(run.agentVersionId()).thenReturn("agent-version");
        when(run.executionConfig()).thenReturn(json.valueToTree(Map.of("agentId", "agent", "config",
            Map.of("pluginVersionIds", List.of(version)), "dependencies", List.of(Map.of("versionId", version,
                "resourceId", resource, "kind", "plugin", "name", "Java 业务插件", "config", configuration)))));
        var binding = catalog.list(run).values().stream().filter(value -> value.source() != null && value.source().type().equals("java")).findFirst().orElseThrow();
        try (var control = new ToolCallControl(() -> {
        })) {
            var context = new ToolProviderContext(enterprise, Duration.ofSeconds(20), control);
            var source = new ToolSource("java", "example", old, "1.0.0", Map.of("configuration", Map.of("prefix", "实例配置")));
            var spec = provider.tools(source, context).getFirst();
            var result = provider.invoke(source, spec, Map.of("value", "已执行"), context);
            assertEquals("实例配置已执行", result.get("content"));
            assertNotEquals(ProcessHandle.current().pid(), ((Number) result.get("pid")).longValue());
        }

        activate(next, old).andExpect(status().isOk());
        assertEquals("upgraded", registry.find(enterprise, old).getStatus());
        assertNull(raw(old).getArtifact());
        var prepared = dependencies.prepareInputOptions(actor, definition);
        assertTrue(prepared.resource().config().path("pluginVersionIds").isEmpty());
        assertTrue(prepared.dependencies().resources().isEmpty());
        assertEquals(version, resources.find(enterprise, agent, false, false).orElseThrow().config().at("/pluginVersionIds/0").asText());
        assertTrue(catalog.list(run).values().stream().noneMatch(value -> value.source() != null && value.source().type().equals("java")));
        ((ObjectNode) run.executionConfig()).put("workflowId", "workflow");
        ((ObjectNode) run.executionConfig().path("config")).putArray("nodes").addObject().put("type", "tool")
            .putObject("config").put("pluginVersionId", version).put("toolId", binding.pluginToolId());
        assertTrue(catalog.all(run).values().stream().noneMatch(value -> value.source() != null && value.source().type().equals("java")));
        assertEquals("JAVA_PLUGIN_UPGRADED", assertThrows(ApiException.class,
            () -> catalog.tool(run, version, binding.pluginToolId())).code());
        assertEquals("JAVA_PLUGIN_UPGRADED", assertThrows(ApiException.class, () -> catalog.requireCurrent(run, binding)).code());
        var notices = data(mvc.perform(get(packages() + "/references").param("versionIds", version).cookie(session.cookie()))
            .andExpect(status().isOk()).andReturn());
        assertEquals("upgraded", notices.get(0).path("status").asText());
        var detail = data(mvc.perform(get(base() + "/resources/" + resource).cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        new ApiContractAssertions().validate("ResourceDetail", detail);
        assertTrue(detail.toString().contains(old));
        assertFalse(detail.toString().contains(next));
        write(HttpMethod.DELETE, packages() + "/" + next, session, null, null).andExpect(status().isOk());
        assertNull(raw(next).getArtifact());
        assertEquals("JAVA_PLUGIN_UNINSTALLED", assertThrows(ApiException.class, () -> registry.require(enterprise, next)).code());
        var removedNotice = data(mvc.perform(get(packages() + "/references").param("versionIds", version).cookie(session.cookie()))
            .andExpect(status().isOk()).andReturn());
        assertEquals("uninstalled", removedNotice.get(0).path("status").asText());
        assertNull(registry.view(registry.find(enterprise, old)).currentInstallationId());
        String reinstalled = upload(firstPackage);
        activate(reinstalled, null).andExpect(status().isOk());
        assertNotEquals(old, reinstalled);
        assertFalse(registry.available(enterprise, old));
        var reinstalledNotice = data(mvc.perform(get(packages() + "/references").param("versionIds", version).cookie(session.cookie()))
            .andExpect(status().isOk()).andReturn());
        assertEquals("upgraded", reinstalledNotice.get(0).path("status").asText());
        verifyInstallationPermissions(reinstalled);
    }





    private void verifyInstallationPermissions(String old) throws Exception {
        String other = enterprises.create("另一个企业", users.findById(user).orElseThrow(), Instant.now()).enterpriseId();
        assertFalse(registry.available(other, old));
        String name = "plugin-member-" + UUID.randomUUID();
        EnterpriseTestData.member(users, permissions, enterprise, name, "Java插件权限验证完整口令2026!", "企业管理员",
            List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        var login = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), Map.of("identifier", name,
            "password", "Java插件权限验证完整口令2026!"), null).andExpect(status().isOk()).andReturn();
        var member = csrf(login.getResponse().getCookie("SESSION"));
        write(HttpMethod.DELETE, packages() + "/" + old, member, null, null).andExpect(status().isForbidden());
        mvc.perform(put(packages() + "/content").cookie(member.cookie()).header("Origin", "http://localhost:3000")
            .header("X-CSRF-Token", member.token()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_OCTET_STREAM).content(secondPackage)).andExpect(status().isForbidden());
    }

    private JavaPluginInstallationRow raw(String id) {
        return installations.selectOne(new LambdaQueryWrapper<JavaPluginInstallationRow>()
            .select(JavaPluginInstallationRow::getArtifact).eq(JavaPluginInstallationRow::getId, id));
    }

    private String upload(byte[] bytes) throws Exception {
        var result = data(mvc.perform(put(packages() + "/content").cookie(session.cookie()).header("Origin", "http://localhost:3000")
            .header("X-CSRF-Token", session.token()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes)).andExpect(status().isOk()).andReturn());
        assertEquals("内容前缀", result.at("/configurationSchema/properties/prefix/title").asText());
        assertEquals("示例工具", result.at("/tools/0/annotations/title").asText());
        assertEquals("object", result.at("/tools/0/inputSchema/type").asText());
        return result.path("id").asText();
    }

    private ResultActions activate(String id, String previous) throws Exception {
        var body = json.createObjectNode().put("previousInstallationId", previous);
        return write(HttpMethod.POST, packages() + "/" + id + "/activate", session, body, null);
    }

    private String base() {
        return "/api/v1/enterprises/" + enterprise;
    }

    private String packages() {
        return base() + "/plugins/java-packages";
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private Session csrf(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie next = response.getResponse().getCookie("SESSION");
        return new Session(next == null ? cookie : next, data(response).path("token").asText());
    }

    private ResultActions write(HttpMethod method, String path, Session login, Object body, String revision) throws Exception {
        var request = request(method, path).cookie(login.cookie()).header("Origin", "http://localhost:3000")
            .header("X-CSRF-Token", login.token()).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(json.writeValueAsBytes(body));
        }
        if (revision != null) {
            request.header("If-Match", "\"" + revision + "\"");
        }
        return mvc.perform(request);
    }
}
