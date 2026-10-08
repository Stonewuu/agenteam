package com.stonewu.agenteam.service.edition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventSqlMapper;
import com.stonewu.agenteam.mapper.edition.InstallationMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.usage.QuotaPolicySqlMapper;
import com.stonewu.agenteam.mapper.usage.QuotaEntryTableMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.model.usage.entity.QuotaPolicyRow;
import com.stonewu.agenteam.model.usage.entity.QuotaEntryRow;
import com.stonewu.agenteam.service.usage.CommunityQuotaPolicyProvider;
import com.stonewu.agenteam.service.usage.QuotaPolicyProvider;
import com.stonewu.agenteam.service.usage.QuotaReservationService;
import com.stonewu.agenteam.model.edition.entity.ProductEdition;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.service.auth.AccountBehaviorExtension;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.export.ExportJobTransactions;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.IdentityHttpClient;
import com.stonewu.agenteam.support.IdentityHttpClient.Session;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** 使用真实默认社区组件，不安装测试发行替代；覆盖网页、内部服务和异常数据路径。 */
@SpringBootTest(properties = "agenteam.demo.worker-enabled=true")
@AutoConfigureMockMvc
class CommunityInitializationApiTest {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    EditionDescriptor descriptor;
    @Autowired
    EnterpriseEditionPolicy policy;
    @Autowired
    InstallationMapper installations;
    @Autowired
    EnterpriseTableMapper enterprises;
    @Autowired
    AuthMapper users;
    @Autowired
    EnterpriseProvisioningService provisioning;
    @Autowired
    ApplicationContext components;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    EnterpriseMapper organization;
    @Autowired
    ResourceVersionMapper versions;
    @Autowired
    QuotaPolicyProvider quotaPolicies;
    @Autowired
    QuotaPolicySqlMapper quotaRows;
    @Autowired
    QuotaEntryTableMapper quotaEntries;
    @Autowired
    QuotaReservationService reservations;
    @Autowired
    ExportJobTransactions exports;
    @Autowired
    AuditEventSqlMapper auditEvents;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    static void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void bootstrapIsAtomicAndEveryCreationAndSelectionPathKeepsTheInitialEnterprise() throws Exception {
        assertThat(descriptor.edition()).isEqualTo(ProductEdition.COMMUNITY);
        assertThat(policy).isInstanceOf(SingleEnterprisePolicy.class);
        var http = new IdentityHttpClient(mvc, json);
        Session first = http.session(null);
        Session second = http.session(null);
        List<MvcResult> responses;
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> bootstrap(http, first, "community-one", ready, start));
            var two = executor.submit(() -> bootstrap(http, second, "community-two", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            responses = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
        assertThat(responses.stream().map(result -> result.getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
        var created = responses.stream().filter(result -> result.getResponse().getStatus() == 201).findFirst().orElseThrow();
        String userId = http.data(created).path("id").asText();
        String enterprise = http.data(created).path("lastEnterpriseId").asText();
        var session = http.session(created.getResponse().getCookie("SESSION"));
        var actor = users.findById(userId).orElseThrow();
        var installation = installations.selectById(1);
        assertThat(installation.getEdition()).isEqualTo("community");
        assertThat(installation.getInitialEnterpriseId()).isEqualTo(enterprise);
        assertThat(installation.getInstallationId()).isEqualTo(UUID.fromString(installation.getInstallationId()).toString());
        assertThat(enterprises.selectCount(new LambdaQueryWrapper<EnterpriseRow>())).isEqualTo(1L);
        http.getJson("/api/v1/enterprises/" + enterprise + "/context", session).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.capabilities", contains("enterprise.invite")));
        http.getJson("/api/v1/auth/me", session).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.capabilities", contains("account.email.change", "account.external.bind", "account.password.change")));
        http.getJson("/api/v1/enterprises/" + enterprise + "/roles", session).andExpect(status().isOk());
        http.getJson("/api/v1/enterprises/" + enterprise + "/search?query=审计", session)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.groups").isEmpty());
        http.getJson("/api/v1/enterprises/" + enterprise + "/search?query=权限目录", session)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.groups").isEmpty());
        var available = http.data(http.getJson("/api/v1/enterprises/" + enterprise + "/permissions", session)
            .andExpect(status().isOk()).andReturn()).findValuesAsText("code");
        assertThat(available).contains("integration.view", "integration.manage", "integration.test", "usage.view",
            "resource.grants.manage", "enterprise.roles.view");
        assertThat(available).doesNotContain("audit.view", "audit.export", "usage.manage", "enterprise.roles.manage");
        http.postJson("/api/v1/enterprises/" + enterprise + "/roles", session, Map.of("name", "不应创建的角色",
                "code", "blocked-role", "description", "", "dataScope", "enterprise", "permissions", List.of("workspace.view")))
            .andExpect(status().isMethodNotAllowed());
        http.postJson("/api/v1/enterprises", session, Map.of("name", "禁止新增企业", "timezone", "Asia/Shanghai",
                "administratorUserId", userId)).andExpect(status().isMethodNotAllowed());
        assertThatThrownBy(() -> provisioning.create("内部新增", actor, Instant.now())).isInstanceOf(ApiException.class)
            .satisfies(failure -> assertThat(((ApiException) failure).code()).isEqualTo("COMMUNITY_SINGLE_ENTERPRISE"));
        assertThatThrownBy(() -> provisioning.createInitial("再次初始化", "", null, "Asia/Shanghai", actor, Instant.now()))
            .isInstanceOf(ApiException.class).satisfies(failure ->
                assertThat(((ApiException) failure).code()).isEqualTo("SYSTEM_ALREADY_INITIALIZED"));
        assertThat(components.getBeanNamesForType(AccountBehaviorExtension.class)).isEmpty();
        http.getJson("/api/v1/system/demo-account", session).andExpect(status().isNotFound());
        http.postJson("/api/v1/system/demo-account/status", session, Map.of("enabled", true)).andExpect(status().isNotFound());
        http.postJson("/api/v1/system/demo-account/cleanup", session, Map.of()).andExpect(status().isNotFound());
        http.postJson("/api/v1/system/demo-account/password", session,
            Map.of("newPassword", "不应创建演示账号的完整测试口令")).andExpect(status().isNotFound());
        assertThat(users.findById("system-demo-user")).isEmpty();
        assertThat(enterprises.selectCount(new LambdaQueryWrapper<EnterpriseRow>())).isEqualTo(1L);

        verifyBasicResourceGrants(http, session, enterprise, userId);
        verifyBasicUsage(http, session, enterprise, userId);
        verifyAuditBoundary(http, session, enterprise, userId);

        // 仅在隔离数据库中构造额外企业和合法成员关系，证明版本范围检查独立于成员检查。
        String injected = injectEnterprise(userId);
        users.addMember(injected, userId, "测试成员", Instant.now());
        http.getJson("/api/v1/enterprises/" + injected + "/context", session).andExpect(status().isNotFound());
        http.postJson("/api/v1/enterprises/" + injected + "/select", session, Map.of()).andExpect(status().isNotFound());
        assertThat(installations.selectById(1).getInitialEnterpriseId()).isEqualTo(enterprise);
        assertThat(users.findById(userId).orElseThrow().lastEnterpriseId()).isEqualTo(enterprise);

        installations.deleteById(1);
        assertThatThrownBy(() -> provisioning.createInitial("丢失记录后重建", "", null, "Asia/Shanghai", actor, Instant.now()))
            .isInstanceOf(ApiException.class).satisfies(failure ->
                assertThat(((ApiException) failure).code()).isEqualTo("INSTALLATION_RECORD_MISSING"));
        assertThat(installations.selectById(1)).isNull();
        assertThat(enterprises.selectCount(new LambdaQueryWrapper<EnterpriseRow>())).isEqualTo(2L);
    }

    private void verifyBasicResourceGrants(IdentityHttpClient http, Session session, String enterprise, String user) throws Exception {
        String root = "/api/v1/enterprises/" + enterprise + "/resources";
        var config = json.readTree("""
            {"icon":"BookOpen","color":"purple","scenario":"","inputDescription":"","instructions":"按输入整理信息。",
             "outputDescription":"","example":"","pluginVersionIds":[],"knowledgeVersionIds":[],"showInWorkspace":false}
            """);
        var request = Map.of("kind", "skill", "name", "基础授权验证技能", "description", "", "tagIds", List.of(), "config", config);
        var resource = http.data(http.postJson(root, session, request).andExpect(status().isCreated()).andReturn());
        String id = resource.at("/resource/id").asText();
        var basic = List.of(Map.of("subjectType", "enterprise", "subjectId", enterprise, "capability", "view"),
            Map.of("subjectType", "user", "subjectId", user, "capability", "use"));
        http.postJson(root + "/" + id + "/publish", session, Map.of("releaseNote", "验证基础授权发布", "grants", basic),
            UUID.randomUUID().toString(), "1").andExpect(status().isCreated());
        assertThat(versions.list(enterprise, id, null, 10)).hasSize(1);
        var before = http.data(http.getJson(root + "/" + id + "/grants", session).andExpect(status().isOk()).andReturn());
        assertThat(before).hasSize(2);
        http.getJson(root + "/" + id + "/subjects?subjectType=user", session).andExpect(status().isOk());
        http.getJson(root + "/" + id + "/subjects?subjectType=team", session).andExpect(status().isUnprocessableEntity());

        String team = UUID.randomUUID().toString();
        organization.insertTeam(team, enterprise, "真实有效团队", "", "active", user, Instant.now());
        var advanced = List.of(Map.of("subjectType", "team", "subjectId", team, "capability", "view"));
        String revision = http.data(http.getJson(root + "/" + id, session).andExpect(status().isOk()).andReturn())
            .at("/resource/revision").asText();
        mvc.perform(put(root + "/" + id + "/grants").cookie(session.cookie()).header("Origin", "http://localhost:3000")
            .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", "\"" + revision + "\"").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("grants", advanced)))).andExpect(status().isUnprocessableEntity());
        assertThat(http.data(http.getJson(root + "/" + id + "/grants", session).andExpect(status().isOk()).andReturn()))
            .isEqualTo(before);

        var draft = http.data(http.postJson(root, session, Map.of("kind", "skill", "name", "不应发布的技能",
            "description", "", "tagIds", List.of(), "config", config)).andExpect(status().isCreated()).andReturn());
        String draftId = draft.at("/resource/id").asText();
        http.postJson(root + "/" + draftId + "/publish", session,
            Map.of("releaseNote", "不允许通过发布写入团队授权", "grants", advanced), UUID.randomUUID().toString(), "1")
            .andExpect(status().isUnprocessableEntity());
        assertThat(versions.list(enterprise, draftId, null, 10)).isEmpty();
        assertThat(http.data(http.getJson(root + "/" + draftId + "/grants", session).andExpect(status().isOk()).andReturn()))
            .isEmpty();
    }

    private void verifyBasicUsage(IdentityHttpClient http, Session session, String enterprise, String user) throws Exception {
        assertThat(quotaPolicies).isInstanceOf(CommunityQuotaPolicyProvider.class);
        var row = quotaRows.selectOne(new LambdaQueryWrapper<QuotaPolicyRow>()
            .eq(QuotaPolicyRow::getEnterpriseId, enterprise));
        assertThat(row.getMonthlyLimit()).isNull();
        String root = "/api/v1/enterprises/" + enterprise;
        http.getJson(root + "/quota-policies", session).andExpect(status().isNotFound());
        http.getJson(root + "/quota-policies/subjects?subjectType=team", session).andExpect(status().isNotFound());
        http.postJson(root + "/quota-policies", session,
            Map.of("subjectType", "enterprise", "subjectId", enterprise, "monthlyLimit", 0, "enabled", true))
            .andExpect(status().isNotFound());
        var actor = new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.of());
        var tx = new TransactionTemplate(transactions);
        String consumed = UUID.randomUUID().toString();
        String cancelled = UUID.randomUUID().toString();
        tx.executeWithoutResult(ignored -> {
            reservations.reserve(actor, consumed);
            reservations.reserve(actor, consumed);
            reservations.reserve(actor, cancelled);
        });
        var pending = http.data(http.getJson(root + "/usage", session).andExpect(status().isOk()).andReturn()).path("items");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).path("reservedCount").asInt()).isEqualTo(2);
        assertThat(pending.get(0).path("usedCount").asInt()).isZero();
        assertThat(pending.get(0).path("monthlyLimit").isNull()).isTrue();
        tx.executeWithoutResult(ignored -> {
            reservations.consume(actor, consumed);
            reservations.consume(actor, consumed);
            reservations.release(enterprise, consumed);
            reservations.release(enterprise, cancelled);
            reservations.release(enterprise, cancelled);
        });
        var settled = http.data(http.getJson(root + "/usage", session).andExpect(status().isOk()).andReturn()).path("items");
        assertThat(settled).hasSize(1);
        assertThat(settled.get(0).path("reservedCount").asInt()).isZero();
        assertThat(settled.get(0).path("usedCount").asInt()).isEqualTo(1);
        assertThat(quotaEntries.selectList(new LambdaQueryWrapper<QuotaEntryRow>()
            .eq(QuotaEntryRow::getEnterpriseId, enterprise))).extracting(QuotaEntryRow::getState)
            .containsExactlyInAnyOrder("consumed", "released");
    }

    private void verifyAuditBoundary(IdentityHttpClient http, Session session, String enterprise, String user) throws Exception {
        String root = "/api/v1/enterprises/" + enterprise + "/audit";
        http.getJson(root, session).andExpect(status().isNotFound());
        http.getJson(root + "/filter-options?kind=actor", session).andExpect(status().isNotFound());
        http.postJson(root + "/export", session,
            Map.of("from", Instant.now().minusSeconds(86400).toString(), "to", Instant.now().toString()))
            .andExpect(status().isNotFound());
        var actor = new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.of("audit.view", "audit.export"));
        assertThatThrownBy(() -> exports.create(actor, new ExportDefinition("audit", Map.of())))
            .isInstanceOf(ApiException.class).satisfies(failure ->
                assertThat(((ApiException) failure).code()).isEqualTo("EXPORT_UNAVAILABLE"));
        assertThat(auditEvents.selectCount(new LambdaQueryWrapper<AuditEventRow>()
            .eq(AuditEventRow::getEnterpriseId, enterprise).eq(AuditEventRow::getAction, "resource.grants.update")))
            .isGreaterThan(0);
    }

    private MvcResult bootstrap(IdentityHttpClient http, Session session, String username, CountDownLatch ready,
                                CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("社区初始化并发测试未启动");
        }
        return http.postJson("/api/v1/auth/bootstrap", session, Map.of("setupCredential", "isolated-invitation-setup-credential",
            "username", username, "displayName", "初始管理员", "password", "社区初始化使用的完整测试口令2026!",
            "enterpriseName", "唯一初始企业", "email", username + "@example.test", "timezone", "Asia/Shanghai"))
            .andReturn();
    }

    private String injectEnterprise(String owner) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("仅用于错误数据检查");
        row.setCreatedBy(owner);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setQuotaPeriodStart(Instant.now());
        row.setQuotaPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        enterprises.insert(row);
        return row.getId();
    }
}
