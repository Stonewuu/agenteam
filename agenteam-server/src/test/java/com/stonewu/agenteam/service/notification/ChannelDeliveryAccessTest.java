package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationSecrets;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationRetentionService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经真实会话验证成员之间、企业之间及全局管理员的发送元数据与正文边界。 */
@Import(SharedEnterpriseTestEdition.class)
class ChannelDeliveryAccessTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private TransactionTemplate tx;
    @Autowired private IntegrationSecretService secrets;
    @Autowired private IntegrationQueryService queries;
    @Autowired private EnterpriseIntegrationMapper connections;
    @Autowired private UserChannelBindingMapper bindings;
    @Autowired private NotificationChannelDeliveryMapper deliveries;
    @Autowired private NotificationWriteService writer;
    @Autowired private ChannelNotificationRouting routing;
    @Autowired private ChannelDeliveryPolicy policy;
    @Autowired private ChannelDeliveryTransactions transactions;
    @Autowired private IdentityQueryMapper members;
    @Autowired private IntegrationRetentionService retention;
    @Autowired private ChannelOauthSessionMapper flows;
    @Autowired private ChannelAuthorizationSecrets authorizationSecrets;
    @Autowired private BackgroundJobSqlMapper jobs;
    private final Set<String> testEnterprises = new HashSet<>();

    @BeforeEach
    void recordTestEnterprise() {
        testEnterprises.add(enterprise);
    }

    @AfterEach
    void stopPendingWork() {
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().in(BackgroundJobRow::getEnterpriseId, testEnterprises)
            .eq(BackgroundJobRow::getKind, "channel_delivery").in(BackgroundJobRow::getStatus, "queued", "leased")
            .set(BackgroundJobRow::getStatus, "cancelled").set(BackgroundJobRow::getLeaseOwner, null).set(BackgroundJobRow::getLeaseUntil, null));
        testEnterprises.clear();
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void recipientCanReadOwnBodyAndMetadataButAnotherMemberAndAnOperatorCannotReadTheBody() throws Exception {
        var recipient = member();
        var other = member();
        var connection = fixture().configure(enterprise, recipient.user(), "feishu");
        var row = notice(connection, recipient.user());
        String bodyPath = base() + "/notifications/" + row.getNotificationId();
        String detailPath = base() + "/notification-deliveries/" + row.getId();
        mvc.perform(get(bodyPath).cookie(recipient.cookie())).andExpect(status().isOk());
        mvc.perform(get(bodyPath).cookie(other.cookie())).andExpect(status().isNotFound());
        mvc.perform(get(bodyPath).cookie(cookie)).andExpect(status().isNotFound());
        var metadata = data(mvc.perform(get(detailPath).cookie(recipient.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("ChannelDeliveryDetail", metadata);
        assertFalse(metadata.toString().contains("只供接收人阅读的正文"));
        assertFalse(metadata.toString().contains("ou_test_member"));
        assertFalse(metadata.at("/delivery/canRetry").asBoolean());
        mvc.perform(get(detailPath).cookie(other.cookie())).andExpect(status().isNotFound());
        mvc.perform(get(detailPath).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get(base() + "/integrations/" + connection.getId() + "/deliveries").cookie(other.cookie())).andExpect(status().isNotFound());
    }

    @Test
    void onlyAuthorizedSenderOrOperatorCanRetryAFailedDelivery() throws Exception {
        var recipient = member();
        var other = member();
        var row = notice(fixture().configure(enterprise, recipient.user(), "feishu"), recipient.user());
        fail(row);
        row = deliveries.selectById(row.getId());
        String path = base() + "/notification-deliveries/" + row.getId() + "/retry";
        for (var browser : List.of(recipient, other)) {
            writeAs(browser, HttpMethod.POST, path, Map.of("confirmMayDuplicate", false), row.getRevision().toString())
                .andExpect(status().isNotFound());
        }
        change(HttpMethod.POST, path, Map.of("confirmMayDuplicate", false), row.getRevision().toString(), key()).andExpect(status().isOk());
    }

    @Test
    void globalAdministratorTestsAnotherEnterpriseWithoutImplicitMembership() throws Exception {
        var owner = member();
        String second = provisioning.create("独立渠道企业", "", null, "Asia/Shanghai", owner.user(), users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        testEnterprises.add(second);
        var connection = fixture().configure(second, owner.user(), "feishu");
        String system = "/api/v1/system/enterprises/" + second + "/integrations/" + connection.getId();
        assertFalse(users.isActiveMember(admin, second));
        var options = data(mvc.perform(get(system + "/recipients").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(owner.user(), options.at("/items/0/id").asText());
        var result = data(change(HttpMethod.POST, system + "/test-messages", Map.of("recipientUserId", owner.user()), "1", key())
            .andExpect(status().isCreated()).andReturn());
        assertEquals("pending", result.at("/delivery/status").asText());
        assertFalse(users.isActiveMember(admin, second));
        mvc.perform(get("/api/v1/enterprises/" + second + "/notifications/" + result.at("/delivery/notificationId").asText()).cookie(cookie))
            .andExpect(status().isNotFound());
        writeAs(owner, HttpMethod.POST, system + "/test-messages", Map.of("recipientUserId", owner.user()), "1").andExpect(status().isForbidden());
        mvc.perform(get(base() + "/notification-deliveries/" + result.at("/delivery/id").asText()).cookie(owner.cookie()))
            .andExpect(status().isNotFound());
    }

    @Test
    void memberDisableAfterQueueingBlocksTheRequestBeforeAnAttemptStarts() throws Exception {
        var recipient = member();
        var row = notice(fixture().configure(enterprise, recipient.user(), "feishu"), recipient.user());
        members.update(new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterprise)
            .eq(EnterpriseMemberRow::getUserId, recipient.user()).set(EnterpriseMemberRow::getStatus, "disabled"));
        var lease = transactions.claim("revoked-member").orElseThrow();
        assertEquals(row.getActiveJobId(), lease.id());
        assertNull(transactions.prepare(lease));
        assertEquals("blocked", deliveries.selectById(row.getId()).getStatus());
        assertEquals(0, deliveries.selectById(row.getId()).getAttemptCount());
    }

    @Test
    void expiredAuthorizationClearsEncryptedMaterialAndLaterRemovesOnlyTheExpiredHistory() throws Exception {
        var first = member();
        var second = member();
        var connection = fixture().configure(enterprise, admin, "feishu");
        var expired = start(first, connection);
        var active = start(second, connection);
        flows.update(new LambdaUpdateWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, enterprise)
            .eq(ChannelOauthSessionRow::getId, expired.getId()).set(ChannelOauthSessionRow::getCreatedAt, Instant.now().minusSeconds(600))
            .set(ChannelOauthSessionRow::getExpiresAt, Instant.now().minusSeconds(1)));
        retention.sweep();
        assertEquals("expired", flows.selectById(expired.getId()).getStatus());
        assertNull(flows.selectById(expired.getId()).getTransientEncryptedJson());
        assertNotNull(flows.selectById(active.getId()).getTransientEncryptedJson());
        flows.update(new LambdaUpdateWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, enterprise)
            .eq(ChannelOauthSessionRow::getId, expired.getId()).set(ChannelOauthSessionRow::getUpdatedAt, Instant.now().minus(Duration.ofDays(8))));
        retention.sweep();
        assertNull(flows.selectById(expired.getId()));
        assertEquals("pending", flows.selectById(active.getId()).getStatus());
    }

    private ChannelOauthSessionRow start(Browser browser, EnterpriseIntegrationRow connection) throws Exception {
        var result = data(writeAs(browser, HttpMethod.POST, base() + "/me/channels/" + connection.getId() + "/authorize",
            Map.of("embeddedClient", false), null).andExpect(status().isOk()).andReturn());
        String state = UriComponentsBuilder.fromUriString(result.path("authorizationUrl").asText()).build().getQueryParams().getFirst("state");
        return flows.selectOne(new LambdaQueryWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, enterprise)
            .eq(ChannelOauthSessionRow::getStateHash, authorizationSecrets.hash(state)));
    }

    private NotificationChannelDeliveryRow notice(EnterpriseIntegrationRow connection, String recipient) {
        return tx.execute(status -> {
            var written = writer.write(enterprise, recipient, new Notice("access:" + key(), "schedule", "指定成员的通知", "只供接收人阅读的正文", null, null), null, admin).orElseThrow();
            return routing.enqueue(written.notification(), policy.capture(enterprise, recipient, connection), "scheduled", Instant.now().plusSeconds(3600));
        });
    }

    private void fail(NotificationChannelDeliveryRow row) {
        var lease = transactions.claim("access-failure").orElseThrow();
        assertEquals(row.getActiveJobId(), lease.id());
        var started = transactions.begin(lease);
        transactions.complete(lease, started.attemptId(), new ChannelSendResult(ChannelSendResult.Outcome.PERMANENT_FAILURE, "230027", null,
            "CHANNEL_PERMISSION_DENIED", "没有发送权限。", null, 400, null));
    }

    private ChannelDeliveryFixtures fixture() {
        return new ChannelDeliveryFixtures(tx, secrets, queries, connections, bindings);
    }

    private Browser member() throws Exception {
        String username = "channel_scope_" + key().substring(0, 8), password = "channel-scope-test-password";
        var user = EnterpriseTestData.member(users, permissions, enterprise, username, password, "通知测试成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var anonymous = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var before = new Browser(anonymous.getResponse().getCookie("SESSION"), data(anonymous).path("token").asText(), user.id());
        var login = writeAs(before, HttpMethod.POST, "/api/v1/auth/login", Map.of("identifier", username, "password", password), null)
            .andExpect(status().isOk()).andReturn();
        Cookie session = login.getResponse().getCookie("SESSION");
        String csrf = data(mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andExpect(status().isOk()).andReturn()).path("token").asText();
        return new Browser(session, csrf, user.id());
    }

    private ResultActions writeAs(Browser browser, HttpMethod method, String path, Object body, String revision) throws Exception {
        var input = request(method, path).cookie(browser.cookie()).header("X-CSRF-Token", browser.csrf()).header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (revision != null) {
            input.header("If-Match", "\"" + revision + "\"");
        }
        return mvc.perform(input);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private record Browser(Cookie cookie, String csrf, String user) {
    }
}
