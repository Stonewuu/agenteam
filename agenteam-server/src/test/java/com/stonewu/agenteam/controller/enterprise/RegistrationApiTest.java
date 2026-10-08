package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.InvitationSqlMapper;
import com.stonewu.agenteam.mapper.http.ApiRequestMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseInvitationRow;
import com.stonewu.agenteam.model.http.entity.ApiRequestRow;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.support.IdentityHttpClient;
import com.stonewu.agenteam.support.IdentityHttpClient.Session;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证分享邀请和手动创建的真实事务、会话与企业访问边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SharedEnterpriseTestEdition.class)
class RegistrationApiTest {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final String PASSWORD = "用于验证邀请注册的完整测试密码";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private AuthMapper users;
    @Autowired
    private PermissionMapper roles;
    @Autowired
    private EnterpriseProvisioningService enterprises;
    @Autowired
    private InvitationSqlMapper invitations;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private ApiRequestMapper requests;
    private IdentityHttpClient client;
    private Session admin;
    private String adminId;
    private String enterprise;
    private String role;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("spring.session.redis.namespace", () -> "agenteam:test:registration");
    }

    @BeforeAll
    void initialize() throws Exception {
        client = new IdentityHttpClient(mvc, json);
        var result = client.postJson("/api/v1/auth/bootstrap", client.session(null), Map.of(
            "setupCredential", "isolated-invitation-setup-credential", "username", "registration-admin",
            "displayName", "注册管理员", "password", PASSWORD, "enterpriseName", "注册测试企业",
            "email", "registration@example.test", "timezone", "Asia/Shanghai")).andExpect(status().isCreated()).andReturn();
        admin = client.session(result.getResponse().getCookie("SESSION"));
        adminId = client.data(result).path("id").asText();
    }

    @AfterAll
    void close() throws Exception {
        ENVIRONMENT.close();
    }

    @BeforeEach
    void prepare() {
        enterprise = enterprises.create("注册隔离企业", users.findById(adminId).orElseThrow(), Instant.now()).enterpriseId();
        role = roles.builtinRoleId(enterprise, "member");
    }

    @Test
    void sharingReplaysSameCredentialAndRegistrationCreatesSessionWithoutEmail() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = share(key);
        assertEquals(first, share(key));
        String code = first.path("code").asText();
        var stored = requests.selectList(Wrappers.<ApiRequestRow>lambdaQuery().eq(ApiRequestRow::getEnterpriseId, enterprise));
        assertTrue(stored.stream().noneMatch(row -> row.getResponseJson().contains(code)));
        assertEquals("/register#token=" + code, first.path("registrationPath").asText());
        client.getJson(base() + "/invitations", admin).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].code").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].tokenHash").doesNotExist());
        String username = "registered-" + UUID.randomUUID();
        var result = register(code, username).andExpect(status().isOk()).andReturn();
        String id = client.data(result).path("id").asText();
        var account = users.findById(id).orElseThrow();
        assertFalse(account.superAdmin());
        assertTrue(passwords.matches(PASSWORD, account.passwordHash()));
        assertTrue(users.isActiveMember(id, enterprise));
        assertEquals(List.of(role), roles.listUserRoleIds(id, enterprise));
        client.getJson("/api/v1/auth/me", client.session(result.getResponse().getCookie("SESSION"))).andExpect(status().isOk());
        register(code, "duplicate-" + UUID.randomUUID()).andExpect(status().isUnauthorized());
    }

    @Test
    void reservedNamesAndConflictsDoNotConsumeInvitation() throws Exception {
        var invite = share(UUID.randomUUID().toString());
        String code = invite.path("code").asText();
        for (String reserved : List.of("test", "TEST", " Test ")) {
            register(code, reserved).andExpect(status().isBadRequest());
            manual(reserved, List.of(role)).andExpect(status().isBadRequest());
        }
        register(code, "registration-admin").andExpect(status().isConflict());
        var row = invitations.selectById(invite.path("invitation").path("id").asText());
        assertEquals("pending", row.getStatus());
        register(code, "valid-" + UUID.randomUUID()).andExpect(status().isOk());
    }

    @Test
    void expiredRevokedAndForeignRolesCannotBeUsed() throws Exception {
        var expired = share(UUID.randomUUID().toString());
        invitations.update(Wrappers.<EnterpriseInvitationRow>lambdaUpdate()
            .eq(EnterpriseInvitationRow::getId, expired.path("invitation").path("id").asText())
            .set(EnterpriseInvitationRow::getExpiresAt, Instant.now().minusSeconds(1)));
        register(expired.path("code").asText(), "expired-user").andExpect(status().isConflict());
        var revoked = share(UUID.randomUUID().toString());
        client.postJson(base() + "/invitations/" + revoked.path("invitation").path("id").asText() + "/revoke", admin,
            Map.of(), UUID.randomUUID().toString(), "1").andExpect(status().isOk());
        register(revoked.path("code").asText(), "revoked-user").andExpect(status().isConflict());
        String other = enterprises.create("另一企业", users.findById(adminId).orElseThrow(), Instant.now()).enterpriseId();
        String foreign = roles.builtinRoleId(other, "member");
        manual("foreign-role", List.of(foreign)).andExpect(status().isNotFound());
        assertTrue(users.findByUsername("foreign-role").isEmpty());
        client.postJson(base() + "/invitations/share", admin, Map.of("roleIds", List.of(foreign), "teamIds", List.of()))
            .andExpect(status().isNotFound());
    }

    @Test
    void manualCreationDoesNotPermitMemberToCreateUsersOrAccessAnotherEnterprise() throws Exception {
        String username = "manual-" + UUID.randomUUID();
        manual(username, List.of(role)).andExpect(status().isCreated());
        manual(username, List.of(role)).andExpect(status().isConflict());
        var result = client.postJson("/api/v1/auth/login", client.session(null), Map.of("identifier", username, "password", PASSWORD))
            .andExpect(status().isOk()).andReturn();
        var member = client.session(result.getResponse().getCookie("SESSION"));
        client.postJson(base() + "/members", member, Map.of("username", "forbidden-user", "password", PASSWORD,
            "roleIds", List.of(role), "teamIds", List.of())).andExpect(status().isForbidden());
        client.postJson(base() + "/invitations/share", member, Map.of("roleIds", List.of(role), "teamIds", List.of()))
            .andExpect(status().isForbidden());
        String other = enterprises.create("不得跨入企业", users.findById(adminId).orElseThrow(), Instant.now()).enterpriseId();
        client.getJson("/api/v1/enterprises/" + other + "/members", member).andExpect(status().isNotFound());
    }

    @Test
    void concurrentAcceptanceCreatesOnlyOneAccount() throws Exception {
        String code = share(UUID.randomUUID().toString()).path("code").asText();
        var sessions = List.of(client.session(null), client.session(null));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = sessions.stream().map(session -> executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return client.postJson("/api/v1/invitations/accept", session,
                        Map.of("token", code, "username", "parallel-" + UUID.randomUUID(), "password", PASSWORD))
                    .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            var results = List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
            assertEquals(List.of(200, 401), results.stream().sorted().toList());
        }
    }

    private JsonNode share(String key) throws Exception {
        return client.data(client.postJson(base() + "/invitations/share", admin,
            Map.of("roleIds", List.of(role), "teamIds", List.of()), key, null).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions register(String code, String username) throws Exception {
        return client.postJson("/api/v1/invitations/accept", client.session(null),
            Map.of("token", code, "username", username, "password", PASSWORD));
    }

    private ResultActions manual(String username, List<String> roleIds) throws Exception {
        return client.postJson(base() + "/members", admin,
            Map.of("username", username, "password", PASSWORD, "roleIds", roleIds, "teamIds", List.of()));
    }

    private String base() {
        return "/api/v1/enterprises/" + enterprise;
    }
}
