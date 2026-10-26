package com.stonewu.agenteam.controller.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.FeishuSetupJobMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.service.integration.feishu.FeishuAppClient;
import com.stonewu.agenteam.service.integration.feishu.FeishuManagementGrantStore;
import com.stonewu.agenteam.service.integration.feishu.FeishuManagementConnections;
import com.stonewu.agenteam.service.integration.feishu.FeishuSetupProcessor;
import com.stonewu.agenteam.service.integration.feishu.FeishuSetupStore;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MockChannelServer;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 一次应用上下文验证完整创建、授权隔离、发布恢复和岗位绑定。 */
@Import({SharedEnterpriseTestEdition.class, FeishuQuickCreateApiTest.PlatformConfiguration.class})
class FeishuQuickCreateApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final MockChannelServer PLATFORM = platform();
    private final List<MockChannelServer.Received> history = new ArrayList<>();
    @Autowired private FeishuSetupStore setup;
    @Autowired private FeishuSetupJobMapper jobs;
    @Autowired private FeishuSetupProcessor processor;
    @Autowired private EnterpriseIntegrationMapper connections;
    @Autowired private ChatBotBindingMapper bots;
    @Autowired private FeishuManagementGrantStore grants;
    @Autowired private FeishuManagementConnections managementConnections;

    @TestConfiguration
    static class PlatformConfiguration {
        @Bean
        @Primary
        IntegrationEndpoints testIntegrationEndpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("agenteam.feishu-setup.worker-enabled", () -> false);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        PLATFORM.close();
        ENVIRONMENT.close();
    }

    @Test
    void createsManagedBotWithoutExposingSecretsOrRepeatingUnknownPublish() throws Exception {
        String requestKey = key();
        var managementInput = Map.of("kind", "management", "name", "创建测试管理应用", "description", "测试应用管理");
        String managementJob = data(write(path() + "/jobs", managementInput, requestKey).andExpect(status().isAccepted()).andReturn()).path("id").asText();
        assertEquals(managementJob, data(write(path() + "/jobs", managementInput, requestKey)
            .andExpect(status().isAccepted()).andReturn()).path("id").asText());
        assertThrows(ApiException.class, () -> setup.require(enterprise, "other-user", managementJob));
        createApp(managementJob, "manager");
        String manager = jobs.selectById(managementJob).getConnectionId();
        assertEquals("draft", connections.selectById(manager).getStatus());
        assertEquals("app_management", connections.selectById(manager).getPurpose());
        assertEquals("飞书确认的manager名称", connections.selectById(manager).getName());
        assertEquals("飞书确认的manager名称", setup.view(jobs.selectById(managementJob)).name());
        completePublishing(managementJob, "manager", false);
        assertEquals("completed", jobs.selectById(managementJob).getStage());
        assertEquals("enabled", connections.selectById(manager).getStatus());

        var authorization = data(write(path() + "/managers/" + manager + "/authorize", Map.of(), key())
            .andExpect(status().isOk()).andReturn());
        var query = UriComponentsBuilder.fromUriString(authorization.path("authorizationUrl").asText()).build().getQueryParams();
        String state = query.getFirst("state");
        assertEquals("S256", query.getFirst("code_challenge_method"));
        String callback = "/api/v1/auth/feishu-management-callbacks/" + manager;
        mvc.perform(get(callback).param("state", state).param("code", "private-code"))
            .andExpect(status().isSeeOther());
        assertNull(grants.find(enterprise, manager, admin));
        reply(Map.of("code", 0, "access_token", "user-management-token", "refresh_token", "refresh-private", "expires_in", 7200,
            "refresh_token_expires_in", 86400, "scope", FeishuAppClient.MANAGE_SCOPE + " offline_access"));
        reply(Map.of("code", 0, "data", Map.of("tenant_key", "tenant-test", "open_id", "ou_manager_user")));
        mvc.perform(get(callback).cookie(cookie).param("state", state).param("code", "private-code")).andExpect(status().isSeeOther());
        capture();
        var grant = grants.find(enterprise, manager, admin);
        assertNotNull(grant);
        assertFalse(grant.getEncryptedJson().contains("user-management-token"));
        assertEquals("authorized", grant.getStatus());
        var authResult = data(mvc.perform(get("/api/v1/auth/feishu-management-result").cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertEquals(manager, authResult.path("connectionId").asText());
        assertTrue(authResult.path("success").asBoolean());
        mvc.perform(get(callback).cookie(cookie).param("state", state).param("code", "private-code")).andExpect(status().isSeeOther());
        assertEquals(0, PLATFORM.remainingRequests());

        // 企业快捷配置复用原应用和个人授权，发布前不提前开启成员能力。
        String enterpriseJob = data(write(path() + "/jobs", Map.of("kind", "enterprise", "name", "企业接入",
            "connectionId", manager, "loginEnabled", true, "messagingEnabled", true), key())
            .andExpect(status().isAccepted()).andReturn()).path("id").asText();
        assertEquals("preparing", jobs.selectById(enterpriseJob).getStage());
        token(); scopes("manager");
        reply(Map.of("code", 0, "data", Map.of("tenant", Map.of("tenant_key", "tenant-test", "name", "测试飞书企业"))));
        reply(Map.of("code", 0, "data", Map.of("app", Map.of("app_name", "复用的企业应用"))));
        step(enterpriseJob);
        assertEquals("configuring", jobs.selectById(enterpriseJob).getStage());
        assertEquals(manager, jobs.selectById(enterpriseJob).getConnectionId());
        assertEquals("app_management", connections.selectById(manager).getPurpose());
        assertFalse(connections.selectById(manager).getBindingEnabled());
        completePublishing(enterpriseJob, "manager", false);
        assertEquals("completed", jobs.selectById(enterpriseJob).getStage());
        var upgraded = connections.selectById(manager);
        assertEquals("enterprise", upgraded.getPurpose());
        assertTrue(upgraded.getBindingEnabled());
        assertTrue(upgraded.getLoginEnabled());
        assertTrue(upgraded.getMessagingEnabled());
        assertTrue(managementConnections.ready(upgraded));
        var systemOverview = data(mvc.perform(get("/api/v1/system/enterprises/" + enterprise + "/chat/feishu").cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertTrue(systemOverview.path("managers").get(0).path("ready").asBoolean());
        assertEquals("enterprise", systemOverview.path("managers").get(0).path("purpose").asText());
        assertTrue(grants.usable(grants.find(enterprise, manager, admin), upgraded));
        assertEquals(1L, upgraded.getCredentialRevision());
        assertEquals("复用的企业应用", setup.view(jobs.selectById(enterpriseJob)).name());
        var configs = history.stream().filter(item -> item.method().equals("PATCH") && item.uri().getPath().endsWith("/cli_manager123/config")).toList();
        var enterpriseConfig = json.readTree(configs.getLast().body());
        assertEquals(2, enterpriseConfig.at("/security/add/redirect_urls").size());
        assertTrue(enterpriseConfig.toString().contains("channel-callbacks/" + manager));
        assertTrue(enterpriseConfig.toString().contains("feishu-management-callbacks/" + manager));
        assertFalse(enterpriseConfig.has("event"));
        upgraded.setCredentialRevision(2L);
        assertFalse(managementConnections.ready(upgraded));
        assertThrows(ApiException.class, () -> setup.manager("another-enterprise", manager));

        String botJob = data(write(path() + "/jobs", Map.of("kind", "bot", "managerConnectionId", manager,
            "agentId", agent, "versionPolicy", "latest_on_new_task", "name", "快捷创建测试机器人", "description", "验证自动绑定"), key())
            .andExpect(status().isAccepted()).andReturn()).path("id").asText();
        createApp(botJob, "bot");
        completePublishing(botJob, "bot", true);
        assertEquals("binding", jobs.selectById(botJob).getStage());
        token();
        reply(Map.of("code", 0, "bot", Map.of("open_id", "ou_created_bot", "app_name", "快捷创建测试机器人")));
        step(botJob);
        var finished = jobs.selectById(botJob);
        assertEquals("completed", finished.getStage());
        var binding = bots.selectById(finished.getBotId());
        assertEquals(agent, binding.getAgentId());
        assertEquals("enabled", binding.getStatus());
        assertTrue(binding.getChatEnabled());
        assertEquals("飞书确认的bot名称", binding.getRoleName());
        assertEquals("飞书确认的bot名称", connections.selectById(finished.getConnectionId()).getName());
        assertEquals(1, bots.selectCount(new LambdaQueryWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getConnectionId, finished.getConnectionId())));
        String response = mvc.perform(get(path() + "/jobs/" + botJob).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("private-secret"));
        assertFalse(response.contains("encryptedJson"));
        assertFalse(response.contains("device-private"));
        assertEquals(1, history.stream().filter(item -> item.method().equals("POST") && item.uri().getPath().endsWith("/cli_bot123/publish")).count());
        assertTrue(history.stream().filter(item -> item.method().equals("PATCH") && item.uri().getPath().contains("/cli_bot123/"))
            .allMatch(item -> "Bearer user-management-token".equals(item.header("Authorization"))));
        assertNull(finished.getEncryptedJson());
        assertFalse(history.stream().anyMatch(item -> item.method().equals("PATCH") && item.uri().getPath().endsWith("/base")),
            "自动配置不得覆盖用户在飞书选择的名称、头像和简介");

        // 一个刷新凭据只允许一个请求使用；密钥版本变化立即使旧授权不可用。
        var refresh = grants.claimRefresh(grants.find(enterprise, manager, admin));
        assertThrows(ChannelProviderException.class, () -> grants.claimRefresh(grants.find(enterprise, manager, admin)));
        grants.refreshed(refresh, json.valueToTree(Map.of("access_token", "refreshed-access", "refresh_token", "refreshed-secret",
            "expires_in", 7200, "refresh_token_expires_in", 86400, "scope", FeishuAppClient.MANAGE_SCOPE + " offline_access")));
        assertEquals("refreshed-access", grants.tokens(grants.find(enterprise, manager, admin)).accessToken());

        String fresh = data(write(path() + "/jobs", Map.of("kind", "enterprise", "name", "新建企业应用"), key())
            .andExpect(status().isAccepted()).andReturn()).path("id").asText();
        createApp(fresh, "enterprise");
        String freshConnection = jobs.selectById(fresh).getConnectionId();
        assertFalse(connections.selectById(freshConnection).getBindingEnabled());
        assertFalse(managementConnections.ready(connections.selectById(freshConnection)));
        completePublishing(fresh, "enterprise", false);
        var freshRow = connections.selectById(freshConnection);
        assertEquals("completed", jobs.selectById(fresh).getStage());
        assertEquals("enterprise", freshRow.getPurpose());
        assertTrue(freshRow.getBindingEnabled());
        assertTrue(freshRow.getMessagingEnabled());
        assertTrue(freshRow.getLoginEnabled());
        assertTrue(managementConnections.ready(freshRow));
        assertNull(grants.find(enterprise, freshConnection, admin));

        String options = data(write(path() + "/jobs", Map.of("kind", "enterprise", "name", "已有企业应用", "connectionId", freshConnection,
            "bindingEnabled", false, "loginEnabled", false, "messagingEnabled", false), key())
            .andExpect(status().isAccepted()).andReturn()).path("id").asText();
        token(); scopes("enterprise");
        reply(Map.of("code", 0, "data", Map.of("tenant", Map.of("tenant_key", "tenant-test", "name", "测试飞书企业"))));
        reply(Map.of("code", 0, "data", Map.of("app", Map.of("app_name", "企业应用"))));
        step(options);
        completePublishing(options, "enterprise", false);
        freshRow = connections.selectById(freshConnection);
        assertFalse(freshRow.getBindingEnabled());
        assertFalse(freshRow.getLoginEnabled());
        assertFalse(freshRow.getMessagingEnabled());
        assertTrue(managementConnections.ready(freshRow));

        String concurrent = data(write(path() + "/jobs", Map.of("kind", "enterprise", "name", "已有企业应用",
            "connectionId", freshConnection), key()).andExpect(status().isAccepted()).andReturn()).path("id").asText();
        connections.update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, freshConnection)
            .eq(EnterpriseIntegrationRow::getRevision, freshRow.getRevision()).set(EnterpriseIntegrationRow::getRevision, freshRow.getRevision() + 1));
        step(concurrent);
        assertEquals("FEISHU_CONNECTION_CHANGED", jobs.selectById(concurrent).getErrorCode());
        assertEquals(0, PLATFORM.remainingRequests());
        var rotated = connections.selectById(manager);
        rotated.setCredentialRevision(rotated.getCredentialRevision() + 1);
        assertFalse(grants.usable(grants.find(enterprise, manager, admin), rotated));

        var wrongTenant = data(write(path() + "/managers/" + manager + "/authorize", Map.of(), key())
            .andExpect(status().isOk()).andReturn());
        String otherState = UriComponentsBuilder.fromUriString(wrongTenant.path("authorizationUrl").asText()).build().getQueryParams().getFirst("state");
        reply(Map.of("code", 0, "access_token", "wrong-tenant-token", "expires_in", 7200, "scope", FeishuAppClient.MANAGE_SCOPE));
        reply(Map.of("code", 0, "data", Map.of("tenant_key", "another-tenant")));
        mvc.perform(get(callback).cookie(cookie).param("state", otherState).param("code", "wrong-tenant-code"))
            .andExpect(status().isSeeOther());
        assertEquals("refreshed-access", grants.tokens(grants.find(enterprise, manager, admin)).accessToken());
    }

    private void createApp(String id, String role) throws Exception {
        reply(Map.of("device_code", "device-private-" + role, "verification_uri_complete",
            "https://open.feishu.cn/page/launcher?user_code=private-link-" + role, "expires_in", 600, "interval", 5));
        step(id);
        assertEquals("awaiting_scan", jobs.selectById(id).getStage());
        assertFalse(jobs.selectById(id).getEncryptedJson().contains("device-private"));
        mvc.perform(get(path() + "/jobs/" + id + "/qr").cookie(cookie)).andExpect(status().isOk());
        reply(Map.of("client_id", "cli_" + role + "123", "client_secret", "private-secret-" + role));
        step(id);
        assertEquals("preparing", jobs.selectById(id).getStage());
        assertFalse(jobs.selectById(id).getEncryptedJson().contains("private-secret"));
        token();
        reply(Map.of("code", 0, "data", Map.of("tenant", Map.of("tenant_key", "tenant-test", "name", "测试飞书企业"))));
        reply(Map.of("code", 0, "data", Map.of("app", Map.of("app_name", "飞书确认的" + role + "名称"))));
        step(id);
        assertEquals("configuring", jobs.selectById(id).getStage());
    }

    private void completePublishing(String id, String role, boolean uncertain) throws Exception {
        if (!role.equals("bot")) {
            token();
        }
        success();
        success();
        step(id);
        assertEquals("publishing", jobs.selectById(id).getStage());
        token();
        app(role, "old");
        version("1.0.0");
        step(id);
        assertEquals("1.0.1", jobs.selectById(id).getExternalVersion());
        token();
        app(role, "old");
        version("1.0.0");
        if (!role.equals("bot")) {
            token();
        }
        if (uncertain) {
            PLATFORM.disconnect();
        } else {
            reply(Map.of("code", 0, "data", Map.of("version_id", "oav_" + role + "_new", "version", "1.0.1")));
        }
        step(id);
        if (uncertain) {
            assertEquals("failed", jobs.selectById(id).getStage());
            setup.change(users.findById(admin).orElseThrow(), enterprise, false, id, jobs.selectById(id).getRevision(), true);
            token();
            app(role, "new");
            version("1.0.1");
            step(id);
        }
        assertEquals("waiting_approval", jobs.selectById(id).getStage());
        token();
        app(role, "new");
        version("1.0.1");
        scopes(jobs.selectById(id).getKind().equals("enterprise") ? "enterprise" : role);
        step(id);
    }

    private void scopes(String role) {
        var scopes = new ArrayList<Map<String, Object>>();
        var names = role.equals("enterprise") ? FeishuAppClient.ENTERPRISE_SCOPES : role.equals("manager") ? FeishuAppClient.MANAGEMENT_SCOPES
            : FeishuAppClient.BOT_SCOPES;
        names.forEach(scope -> scopes.add(Map.of("scope_name", scope, "scope_type", "tenant", "grant_status", 1)));
        if (!role.equals("bot")) {
            scopes.add(Map.of("scope_name", FeishuAppClient.MANAGE_SCOPE, "scope_type", "user", "grant_status", 1));
            scopes.add(Map.of("scope_name", "offline_access", "scope_type", "user", "grant_status", 1));
        }
        reply(Map.of("code", 0, "data", Map.of("scopes", scopes)));
    }

    private void app(String role, String version) {
        reply(Map.of("code", 0, "data", Map.of("app", Map.of("online_version_id", "oav_" + role + "_" + version, "unaudit_version_id", ""))));
    }

    private void version(String version) {
        reply(Map.of("code", 0, "data", Map.of("app_version", Map.of("version", version, "status", 1))));
    }

    private void token() {
        reply(Map.of("code", 0, "tenant_access_token", "app-token", "expire", 7200));
    }

    private void success() {
        reply(Map.of("code", 0, "data", Map.of()));
    }

    private void reply(Object value) {
        PLATFORM.raw(200, json.valueToTree(value).toString(), Map.of());
    }

    private void step(String id) throws Exception {
        var before = jobs.selectById(id);
        var lease = setup.claim(before, "test-worker");
        assertNotNull(lease);
        assertNull(setup.claim(before, "another-worker"));
        processor.process(lease);
        capture();
    }

    private void capture() throws Exception {
        while (PLATFORM.remainingRequests() > 0) {
            history.add(PLATFORM.take());
        }
    }

    private String path() {
        return base() + "/chat/feishu";
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private static MockChannelServer platform() {
        try {
            return new MockChannelServer();
        } catch (Exception failure) {
            throw new IllegalStateException("测试平台无法启动", failure);
        }
    }
}
