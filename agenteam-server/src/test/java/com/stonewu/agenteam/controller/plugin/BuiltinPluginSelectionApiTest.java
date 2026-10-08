package com.stonewu.agenteam.controller.plugin;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.configuration.plugin.BuiltinPluginResourceInitialization;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.resource.response.UsableVersionView;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import com.stonewu.agenteam.service.permission.BuiltinRoleCatalog;
import com.stonewu.agenteam.service.plugin.BuiltinPluginResourceService;
import com.stonewu.agenteam.service.resource.UsableVersionService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证选择器实际读取发布版本，覆盖已有企业补齐和管理员已调整的可用范围。
 */
@Import(SharedEnterpriseTestEdition.class)
class BuiltinPluginSelectionApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private BuiltinPluginResourceService defaults;
    @Autowired
    private BuiltinPluginResourceInitialization initialization;
    @Autowired
    private EnterpriseMapper enterprises;
    @Autowired
    private BuiltinRoleCatalog roles;
    @Autowired
    private ResourceMapper resources;
    @Autowired
    private ResourceAuthorizationMapper grants;
    @Autowired
    private PluginToolMapper tools;
    @Autowired
    private UsableVersionService options;
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
    void newEnterpriseImmediatelyOffersRealPublishedBuiltinPlugins() throws Exception {
        var response = data(mvc.perform(get(base() + "/resources/usable-versions").param("kind", "plugin").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(4, response.path("items").size());
        int toolCount = 0;
        for (var value : response.path("items")) {
            schemas.validate("UsableVersion", value);
            String id = value.path("resourceId").asText(), versionId = value.path("versionId").asText();
            var resource = resources.find(enterprise, id, false, false).orElseThrow();
            assertEquals("builtin", resource.source());
            assertEquals(versionId, resource.publishedVersionId());
            assertEquals(1, value.path("versionNo").asInt());
            var published = tools.list(enterprise, versionId);
            assertFalse(published.isEmpty());
            toolCount += published.size();
            assertEquals(resource.config().path("enabledToolNames").size(), published.size());
        }
        assertEquals(31, toolCount);
        assertFalse(response.toString().contains("inputSchema"));
    }

    @Test
    void startupFillsExistingEnterpriseWithoutReplacingItsCustomPlugin() {
        String legacy = legacyEnterprise();
        String custom = UUID.randomUUID().toString();
        var config = json.createObjectNode().put("icon", "Box").put("color", "pink").put("pluginType", "mcp").put("endpoint", "https://example.com/mcp");
        resources.create(custom, legacy, ResourceKind.PLUGIN, "待办管理", "已有自定义插件", "mcp", admin, "created", config, Instant.now());
        initialization.run(new DefaultApplicationArguments());
        assertEquals(4, available(actor(legacy)).size());
        var unchanged = resources.find(legacy, custom, false, false).orElseThrow();
        assertEquals(config, unchanged.config());
        assertNull(unchanged.publishedVersionId());
        assertEquals(1, unchanged.revision());
        var first = available(actor(legacy));
        initialization.run(new DefaultApplicationArguments());
        assertEquals(first, available(actor(legacy)));
        assertEquals(5, pluginCount(legacy));
    }

    @Test
    void concurrentInitializationPublishesEachPluginOnlyOnce() throws Exception {
        String legacy = legacyEnterprise();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> defaults.initialize(legacy));
            var second = executor.submit(() -> defaults.initialize(legacy));
            first.get();
            second.get();
        }
        assertEquals(4, pluginCount(legacy));
        for (var value : available(actor(legacy))) {
            assertEquals(1, value.versionNo());
            assertEquals(2, resources.find(legacy, value.resourceId(), false, false).orElseThrow().nextVersionNo());
        }
    }

    @Test
    void choosingBuiltinsNeedsUsagePermissionButNoPluginCreationOrPublicationPermission() {
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "builtin-selector", "只配置智能体", "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("capabilities.view", "agent.edit", "plugin.invoke"));
        var user = EnterpriseTestData.member(users, permissions, enterprise, "selector-" + UUID.randomUUID(), "内置插件选择测试所用独立密码2026!", "配置者", List.of(role));
        var actor = new AuthContext(user, enterprise, Set.of());
        assertEquals(4, available(actor).size());
        permissions.replaceRolePermissions(enterprise, role, Set.of("capabilities.view", "agent.edit"));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> available(actor)).getStatusCode().value());
    }

    @Test
    void initializationPreservesDisabledStateAndRemovedUseGrants() {
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "builtin-reader", "配置读取者", "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("capabilities.view", "agent.edit", "plugin.invoke"));
        var user = EnterpriseTestData.member(users, permissions, enterprise, "reader-" + UUID.randomUUID(), "内置插件范围测试所用独立密码2026!", "配置者", List.of(role));
        var actor = new AuthContext(user, enterprise, Set.of());
        var choices = available(actor);
        var disabled = resources.find(enterprise, choices.get(0).resourceId(), false, false).orElseThrow();
        var restricted = choices.get(1);
        resources.status(disabled, "disabled", Instant.now());
        grants.replaceGrants(enterprise, restricted.resourceId(), admin, Set.of(), Instant.now());
        defaults.initialize(enterprise);
        assertEquals("disabled", resources.find(enterprise, disabled.id(), false, false).orElseThrow().status());
        assertTrue(grants.grants(enterprise, restricted.resourceId()).isEmpty());
        assertEquals(2, available(actor).size());
        assertTrue(options.list(actor, "plugin", null, null, 20, List.of(disabled.publishedVersionId(), restricted.versionId())).items().isEmpty());
        assertEquals(4, pluginCount(enterprise));
    }

    @Test
    void resourcePickerGroupsLatestVersionsAndListsAuthorizedHistorySeparately() throws Exception {
        var config = json.createObjectNode().put("icon", "Box").put("color", "mint").put("pluginType", "builtin").put("builtinCode", "platform_basics")
            .putNull("transport").putNull("endpoint").putNull("credentialId").put("timeoutSeconds", 30);
        config.set("enabledToolNames", json.valueToTree(List.of("platform_time")));
        String id = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "版本选择回归", "description", "验证分组与版本选择", "tagIds", List.of(), "config", config), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String first = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "第一版"), "1", UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String second = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "第二版"), "2", UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String path = base() + "/resources/usable-versions";
        var latest = data(mvc.perform(get(path).cookie(cookie).param("kind", "plugin").param("latestOnly", "true").param("query", "版本选择回归")).andExpect(status().isOk()).andReturn());
        assertEquals(1, latest.path("items").size());
        assertEquals(second, latest.at("/items/0/versionId").asText());
        var history = data(mvc.perform(get(path).cookie(cookie).param("kind", "plugin").param("resourceId", id).param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertTrue(history.path("hasMore").asBoolean());
        assertEquals(second, history.at("/items/0/versionId").asText());
        var older = data(mvc.perform(get(path).cookie(cookie).param("kind", "plugin").param("resourceId", id).param("limit", "1").param("cursor", history.path("nextCursor").asText())).andExpect(status().isOk()).andReturn());
        assertEquals(first, older.at("/items/0/versionId").asText());
        assertEquals(first, options.list(actor(enterprise), "plugin", null, null, 20, List.of(first)).items().getFirst().versionId());
        versions.revoke(enterprise, second, Instant.now());
        assertEquals(first, options.list(actor(enterprise), "plugin", "版本选择回归", null, 20, null, false, null, true).items().getFirst().versionId());
        String other = legacyEnterprise();
        defaults.initialize(other);
        String foreign = available(actor(other)).getFirst().resourceId();
        assertTrue(options.list(actor(enterprise), "plugin", null, null, 20, null, false, foreign, false).items().isEmpty());
    }

    private AuthContext actor(String id) {
        return new AuthContext(users.findById(admin).orElseThrow(), id, Set.of());
    }

    private List<UsableVersionView> available(AuthContext actor) {
        return options.list(actor, "plugin", null, null, 20, null).items();
    }

    private long pluginCount(String id) {
        return databaseAccess.mapper(ResourceSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, id).eq(ResourceRow::getKind, "plugin"));
    }

    private String legacyEnterprise() {
        String id = "legacy-" + UUID.randomUUID();
        var now = Instant.now();
        enterprises.insert(id, "已有企业", "", null, admin, QuotaPeriod.containing(now, "Asia/Shanghai"), now);
        users.addMember(id, admin, "执行管理员", now);
        var createdRoles = roles.createRoles(permissions, id, now);
        permissions.replaceUserRoles(admin, id, Set.of(createdRoles.get("enterprise-admin")), now);
        enterprises.createDefaultQuota(id, null, now);
        return id;
    }
}
