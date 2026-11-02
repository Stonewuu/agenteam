package com.stonewu.agenteam.controller.integration;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelIdentity;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelBindingService;
import com.stonewu.agenteam.service.integration.ChannelOauthStore;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过正式接口和独立数据库验证接入删除、历史残留绑定及重新接入。 */
@Import(SharedEnterpriseTestEdition.class)
class ChannelBindingLifecycleApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private ChannelBindingService bindingService;
    @Autowired private ChannelOauthStore authorizations;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void deletingConnectionRevokesOnlyItsBindingsAndPendingAuthorizations() throws Exception {
        var original = connection("feishu", key(), "enabled");
        var other = connection("feishu", original.getExternalTenantId(), "enabled");
        var selected = binding(original, admin, "shared-member");
        var preserved = binding(other, admin, "shared-member");
        var session = new MockHttpSession();
        var flow = authorizations.create(original, "login", null, key(), key(), key(), session);
        change(HttpMethod.PATCH, integration(original) + "/status", Map.of("status", "disabled"), "1", key())
            .andExpect(status().isOk());
        change(HttpMethod.DELETE, integration(original), null, "2", key()).andExpect(status().isOk());
        assertRevoked(selected);
        assertEquals("active", account(preserved).getStatus());
        var updated = databaseAccess.mapper(ChannelOauthSessionMapper.class).selectById(flow.getId());
        assertEquals("expired", updated.getStatus());
        assertNull(updated.getTransientEncryptedJson());
        assertNull(updated.getConfirmationTokenHash());
        assertFalse(databaseAccess.mapper(EnterpriseIntegrationMapper.class).selectById(original.getId()).getLoginEnabled());
        var replacement = connection("feishu", original.getExternalTenantId(), "enabled");
        assertNotEquals(original.getId(), replacement.getId());
        assertEquals("active", account(preserved).getStatus());
    }

    @Test
    void orphanBindingCanBeRevokedWithoutAnyOtherConnectionInItsScope() throws Exception {
        var original = connection("feishu", key(), "deleted");
        var orphan = binding(original, admin, "old-member");
        var unrelated = binding(connection("feishu", key(), "enabled"), admin, "old-member");
        change(HttpMethod.DELETE, channels() + "/" + orphan.getId(), null, "1", key()).andExpect(status().isOk());
        assertRevoked(orphan);
        assertEquals("active", account(unrelated).getStatus());
        change(HttpMethod.DELETE, channels() + "/" + orphan.getId(), null, "1", key()).andExpect(status().isNotFound());
    }

    @Test
    void orphanCannotHideReplacementAndSharedUnbindCannotCrossTenantOrProvider() throws Exception {
        var old = connection("feishu", key(), "deleted");
        var orphan = binding(old, admin, "same-member");
        var current = connection("feishu", old.getExternalTenantId(), "enabled");
        var view = data(mvc.perform(get(channels()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        var oldView = view.findParents("connectionId").stream().filter(value -> old.getId().equals(value.path("connectionId").asText())).findFirst().orElseThrow();
        assertEquals("disabled", oldView.path("status").asText());
        assertTrue(view.findParents("connectionId").stream().anyMatch(value -> current.getId().equals(value.path("connectionId").asText())
            && value.path("id").isNull() && value.path("bindingAvailable").asBoolean()));
        var otherUser = EnterpriseTestData.member(users, permissions, enterprise, "binding_" + key(), "binding-member-password",
            "另一名成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var shared = binding(current, admin, "same-member");
        var otherAccount = binding(current, otherUser.id(), "another-member");
        var otherTenant = binding(connection("feishu", key(), "enabled"), admin, "same-member");
        var otherProvider = binding(connection("wecom", old.getExternalTenantId(), "enabled"), admin, "same-member");
        change(HttpMethod.DELETE, channels() + "/" + orphan.getId(), null, "1", key()).andExpect(status().isOk());
        assertRevoked(orphan);
        assertRevoked(shared);
        for (var preserved : List.of(otherAccount, otherTenant, otherProvider)) {
            assertEquals("active", account(preserved).getStatus());
        }
    }

    @Test
    void disabledConnectionShowsUnavailableAndCannotEnableBindingOptions() throws Exception {
        var disabled = connection("feishu", key(), "disabled");
        var bound = binding(disabled, admin, "member");
        var list = data(mvc.perform(get(channels()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals("disabled", list.get(0).path("status").asText());
        assertFalse(list.get(0).path("loginAvailable").asBoolean());
        change(HttpMethod.PATCH, channels() + "/" + bound.getId(), Map.of("receiveEnabled", true,
            "chatEnabled", true, "externalLoginEnabled", true), "1", key()).andExpect(status().isConflict());
        assertEquals(1, account(bound).getRevision());
        change(HttpMethod.DELETE, channels() + "/" + bound.getId(), null, "1", key()).andExpect(status().isOk());
        assertRevoked(bound);
    }

    @Test
    void loginRequiresCurrentApplicationBindingAndExplicitLoginChoice() {
        var old = connection("feishu", key(), "deleted");
        var original = binding(old, admin, "shared");
        var replacement = connection("feishu", old.getExternalTenantId(), "enabled");
        assertEquals("CHANNEL_LOGIN_UNAVAILABLE", assertThrows(ApiException.class, () -> login(replacement, original)).code());
        var bound = binding(replacement, admin, "shared");
        assertEquals("CHANNEL_LOGIN_NOT_ENABLED", assertThrows(ApiException.class, () -> login(replacement, bound)).code());
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, bound.getId()).set(UserChannelBindingRow::getExternalLoginEnabled, true));
        assertEquals(admin, login(replacement, bound).user().id());
    }

    private ChannelBindingService.LoginResult login(EnterpriseIntegrationRow connection, UserChannelBindingRow binding) {
        var session = new MockHttpSession();
        String state = key();
        String nonce = key();
        var flow = authorizations.create(connection, "login", null, state, nonce, key(), session);
        authorizations.claim(connection.getId(), state, nonce, session);
        return bindingService.login(flow.getId(), nonce, new ChannelIdentity(connection.getExternalTenantId(), binding.getExternalSubjectType(),
            binding.getExternalSubjectId(), binding.getExternalUnionId(), "测试成员"), session);
    }

    private EnterpriseIntegrationRow connection(String provider, String tenant, String status) {
        var row = new EnterpriseIntegrationRow();
        row.setId(key());
        row.setEnterpriseId(enterprise);
        row.setProviderCode(provider);
        row.setPurpose("enterprise");
        row.setName("绑定生命周期测试");
        row.setExternalTenantId(tenant);
        row.setExternalAppId("cli_" + key());
        row.setPublicLoginKey(key().replace("-", ""));
        row.setStatus(status);
        row.setBindingEnabled(true);
        row.setLoginEnabled(true);
        row.setMessagingEnabled(true);
        row.setIdentityVerifiedAt(Instant.now());
        row.setCreatedBy(admin);
        row.setUpdatedBy(admin);
        if (status.equals("deleted")) {
            row.setDeletedAt(Instant.now());
            row.setDeletedToken(row.getId());
        }
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return databaseAccess.mapper(EnterpriseIntegrationMapper.class).selectById(row.getId());
    }

    private UserChannelBindingRow binding(EnterpriseIntegrationRow connection, String user, String subject) {
        var row = new UserChannelBindingRow();
        row.setId(key());
        row.setEnterpriseId(enterprise);
        row.setConnectionId(connection.getId());
        row.setUserId(user);
        row.setExternalSubjectType(connection.getProviderCode().equals("wecom") ? "wecom_userid" : "feishu_open_id");
        row.setExternalSubjectId(subject);
        row.setExternalUnionId(subject);
        row.setChatEnabled(true);
        row.setAuthorizedAt(Instant.now());
        databaseAccess.mapper(UserChannelBindingMapper.class).insert(row);
        return account(row);
    }

    private UserChannelBindingRow account(UserChannelBindingRow row) {
        return databaseAccess.mapper(UserChannelBindingMapper.class).selectById(row.getId());
    }

    private void assertRevoked(UserChannelBindingRow row) {
        var updated = account(row);
        assertEquals("revoked", updated.getStatus());
        assertEquals(row.getRevision() + 1, updated.getRevision());
        assertNotNull(updated.getRevokedAt());
        assertFalse(updated.getChatEnabled());
        assertFalse(updated.getReceiveEnabled());
        assertFalse(updated.getExternalLoginEnabled());
    }

    private String channels() {
        return base() + "/me/channels";
    }

    private String integration(EnterpriseIntegrationRow connection) {
        return base() + "/integrations/" + connection.getId();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
