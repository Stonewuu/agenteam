package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.request.ChatCheckRequest;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 连接接管、探针、配对和过期清理在真实数据库事务中验证。 */
class ChatConnectionLifecycleTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.wecom-aibot.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }
    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }
    @Autowired ChatConnectionTransactions connections;
    @Autowired ChatCapabilityService capabilities;
    @Autowired ChatRetentionService retention;
    @Autowired ChatOutboundQueryService outgoing;
    @Autowired ChatTestMemberService testMembers;

    @Test
    void aFailedCredentialCheckReplacesThePreviousPassedResult() {
        capabilities.evidence(bot, "credential", Map.of("status", "passed"));
        assertTrue(bots.capability(bots.require(enterprise, bot.getId()), "credential"));
        var prepared = capabilities.prepare(enterprise, bot.getId(), new ChatCheckRequest(1L, null, "credential", null));
        assertEquals("failed", capabilities.checked(prepared).get("status"));
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "credential"));
    }

    @Test
    void expiredReceiveProbesAreRemovedWithoutChangingBusinessConfiguration() throws Exception {
        capabilities.receive(initiator, enterprise, bot.getId(), new ChatCheckRequest(1L, group.getId(), "text", binding.getId()));
        var config = (ObjectNode) json.readTree(bots.require(enterprise, bot.getId()).getConfigJson());
        config.withObject("/receiveProbe").put("expiresAt", Instant.now().minusSeconds(1).toString());
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId())
            .set(ChatBotBindingRow::getConfigJson, config.toString()));
        capabilities.expireProbes();
        var current = bots.require(enterprise, bot.getId());
        assertFalse(json.readTree(current.getConfigJson()).has("receiveProbe"));
        assertEquals(1L, current.getRevision());
        assertEquals("enabled", current.getStatus());
    }

    @Test
    void testMemberChoicesContainOnlyExplicitlyEnabledBindingsOfThisApplication() {
        var page = testMembers.list(admin, enterprise, bot.getId(), null, null, 30);
        assertEquals(1, page.items().size());
        assertEquals(binding.getId(), page.items().getFirst().bindingId());
        assertEquals(initiator.username(), page.items().getFirst().username());
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getChatEnabled, false));
        assertTrue(testMembers.list(admin, enterprise, bot.getId(), null, null, 30).items().isEmpty());
    }

    @Test
    void sharedFeishuIdentityCanVerifyTwoBotsWithOneAccountBinding() {
        var finance = secondBot();
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getExternalUnionId, "on_joint_member"));
        assertEquals(binding.getId(), testMembers.list(admin, enterprise, finance.getId(), null, null, 30).items().getFirst().bindingId());
        var probe = capabilities.receive(initiator, enterprise, bot.getId(),
            new ChatCheckRequest(1L, group.getId(), "text", binding.getId(), finance.getId()));
        var event = message("joint-shared-identity", "joint-shared-identity", "user", probe.get("marker").toString());
        event.withObject("/event/sender/sender_id").put("union_id", "on_joint_member");
        event.withArray("/event/message/mentions").addObject().put("name", "财务").putObject("id").put("open_id", finance.getPlatformBotId());
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        event.withObject("/event/sender/sender_id").put("open_id", "ou_member_in_finance_app");
        ingress.accept(enterprise, finance.getId(), 1L, event);
        normalizeAll();
        assertTrue(bots.capability(bots.require(enterprise, bot.getId()), "crossBotIdentity"));
        assertTrue(bots.capability(bots.require(enterprise, finance.getId()), "crossBotIdentity"));
        assertEquals(0, taskCount());
    }

    @Test
    void twoNodesCannotOwnTheSameConnectionAndOldConfigurationCannotReceive() throws Exception {
        var mapper = databaseAccess.mapper(ChatBotBindingMapper.class);
        mapper.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId()).set(ChatBotBindingRow::getLeaseUntil, null));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> connections.claim(enterprise, bot.getId(), "node-one"));
            var two = executor.submit(() -> connections.claim(enterprise, bot.getId(), "node-two"));
            var leases = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)).stream().flatMap(value -> value.stream()).toList();
            assertEquals(1, leases.size());
            var lease = leases.getFirst();
            assertTrue(connections.renew(lease));
            assertFalse(connections.renew(new ChatConnectionTransactions.Lease(enterprise, bot.getId(), "wrong-owner", lease.version(), lease.configurationRevision(), lease.application())));
            mapper.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId()).setIncrBy(ChatBotBindingRow::getRevision, 1));
            assertFalse(connections.renew(lease));
            assertThrows(ApiException.class, () -> ingress.acceptVerified(enterprise, bot.getId(), lease.version(), lease.application().credentialRevision(),
                lease.configurationRevision(), "websocket", message("changed-config", "changed-config", "user", "旧连接不能继续处理")));
            assertEquals(0, messageCount());
        }
    }

    @Test
    void replacementStopsAutomaticReconnectionUntilAdministratorUpdatesConfiguration() {
        var mapper = databaseAccess.mapper(ChatBotBindingMapper.class);
        mapper.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId()).set(ChatBotBindingRow::getLeaseUntil, null));
        var lease = connections.claim(enterprise, bot.getId(), "test-node").orElseThrow();
        connections.release(lease, true);
        assertTrue(connections.claim(enterprise, bot.getId(), "another-node").isEmpty());
        mapper.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId()).setIncrBy(ChatBotBindingRow::getRevision, 1));
        assertTrue(connections.claim(enterprise, bot.getId(), "another-node").isPresent());
    }

    @Test
    void receiveProbeNeedsExactSenderAndMentionAndNeverCreatesTask() {
        var probe = capabilities.receive(initiator, enterprise, bot.getId(), new ChatCheckRequest(1L, group.getId(), "text", binding.getId()));
        String marker = probe.get("marker").toString();
        var wrong = message("probe-wrong", "probe-wrong", "user", marker);
        wrong.withObject("/event/sender/sender_id").put("open_id", "another-user");
        ingress.accept(enterprise, bot.getId(), 1L, wrong);
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "receiveMention"));
        ingress.accept(enterprise, bot.getId(), 1L, message("probe-right", "probe-right", "user", marker));
        normalizeAll();
        assertTrue(bots.capability(bots.require(enterprise, bot.getId()), "receiveMention"));
        assertEquals(0, messageCount());
        assertEquals(0, taskCount());
    }

    @Test
    void acceptedSendProbeRecordsEvidenceAndUnknownCannotBeRetried() {
        var result = capabilities.send(enterprise, bot.getId(), new ChatCheckRequest(1L, group.getId(), "text", null), "one-probe");
        var lease = sending.claim("test-sender").orElseThrow();
        assertEquals(result.get("outboundId"), lease.outboundId());
        sending.begin(lease);
        sending.complete(lease, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "0", "platform-probe", null, null, null, 200, "trace"), null));
        assertTrue(bots.capability(bots.require(enterprise, bot.getId()), "sendText"));
        var mapper = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        mapper.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, lease.outboundId()).set(ChatOutboundMessageRow::getStatus, "unknown"));
        var row = mapper.selectById(lease.outboundId());
        assertThrows(ApiException.class, () -> outgoing.retry(enterprise, row.getId(), row.getRevision()));
    }

    @Test
    void ordinaryBusinessTextWithVerificationPrefixStillCreatesATask() {
        var incoming = receive("business-verification", "群策能力验证-业务-20261014-A：请核对合成考勤");
        assertNotNull(incoming);
        routing.route(enterprise, incoming.getId());
        assertEquals(1, taskCount());
        assertEquals(1, messageCount());
        // 本例只验证入站消息，取消它产生的待发确认，避免影响其他用例的全局发送领取。
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getTaskId, onlyTask().getId())
            .eq(ChatOutboundMessageRow::getStatus, "pending").set(ChatOutboundMessageRow::getStatus, "cancelled"));
    }

    @Test
    void expiredReceiveMarkerStillCannotCreateATask() throws Exception {
        var probe = capabilities.receive(initiator, enterprise, bot.getId(), new ChatCheckRequest(1L, group.getId(), "text", binding.getId()));
        var mapper = databaseAccess.mapper(ChatBotBindingMapper.class);
        var config = (ObjectNode) json.readTree(bots.require(enterprise, bot.getId()).getConfigJson());
        config.withObject("/receiveProbe").put("expiresAt", Instant.now().minusSeconds(1).toString());
        mapper.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId())
            .set(ChatBotBindingRow::getConfigJson, config.toString()));
        ingress.accept(enterprise, bot.getId(), 1L, message("expired-probe", "expired-probe", "user", probe.get("marker").toString()));
        normalizeAll();
        assertFalse(bots.capability(bots.require(enterprise, bot.getId()), "receiveMention"));
        assertEquals(0, messageCount());
        assertEquals(0, taskCount());
    }

    @Test
    void realHttpPairingCodeIsPrivateOneTimeAndNeverRetainedInEvent() throws Exception {
        wecom();
        String code = pairingCode();
        var groupEvent = wecomMessage("group-code", code, false);
        ingress.accept(enterprise, bot.getId(), 1L, groupEvent);
        assertEquals(0, activeBindings());
        var event = wecomMessage("private-code", code, true);
        String receiptId = ingress.accept(enterprise, bot.getId(), 1L, event);
        assertEquals(1, activeBindings());
        assertEquals(receiptId, ingress.accept(enterprise, bot.getId(), 1L, event));
        ingress.accept(enterprise, bot.getId(), 1L, wecomMessage("replay-code", code, true));
        assertEquals(1, activeBindings());
        var receipt = receipts().selectById(receiptId);
        assertNotNull(receipt.getPayloadPurgedAt());
        assertFalse(cipher.decrypt(enterprise, "receipt", receiptId, receipt.getPayloadEncrypted()).toString().contains(code));
        var saved = databaseAccess.mapper(UserChannelBindingMapper.class).selectOne(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getStatus, "active"));
        assertEquals("wecom_aibot_userid", saved.getExternalSubjectType());
        assertTrue(saved.getChatEnabled());
        assertFalse(saved.getExternalLoginEnabled());
        assertEquals(0, taskCount());
    }

    @Test
    void fiveWrongPairingAttemptsInvalidateTheCode() throws Exception {
        wecom();
        String code = pairingCode();
        String wrong = code.substring(0, code.indexOf('.') + 1) + "incorrect";
        for (int index = 0; index < 5; index++) {
            ingress.accept(enterprise, bot.getId(), 1L, wecomMessage("wrong-" + index, wrong, true));
        }
        ingress.accept(enterprise, bot.getId(), 1L, wecomMessage("sixth-correct", code, true));
        assertEquals(0, activeBindings());
        var flow = databaseAccess.mapper(ChannelOauthSessionMapper.class).selectById(code.substring(0, code.indexOf('.')));
        assertEquals("failed", flow.getStatus());
        assertEquals(5, flow.getPairingAttempts());
    }

    @Test
    void anExpiredPrivatePairingCodeCannotBindAnAccount() throws Exception {
        wecom();
        String code = pairingCode();
        databaseAccess.mapper(ChannelOauthSessionMapper.class).update(new LambdaUpdateWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getId, code.substring(0, code.indexOf('.')))
            .set(ChannelOauthSessionRow::getCreatedAt, Instant.now().minusSeconds(600))
            .set(ChannelOauthSessionRow::getExpiresAt, Instant.now().minusSeconds(1)));
        ingress.accept(enterprise, bot.getId(), 1L, wecomMessage("expired-private-code", code, true));
        assertEquals(0, activeBindings());
        assertEquals(0, taskCount());
    }

    @Test
    void clearingOldRawEventsPreservesTheDedupeRecord() {
        var event = message("old-raw", "old-raw", "user", "原始消息正文");
        String id = ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        receipts().update(new LambdaUpdateWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getId, id)
            .set(ChatInboundReceiptRow::getReceivedAt, Instant.now().minusSeconds(90000)));
        retention.clean();
        assertNotNull(receipts().selectById(id).getPayloadPurgedAt());
        assertEquals(id, ingress.accept(enterprise, bot.getId(), 1L, event));
        assertEquals(1, messageCount());
    }

    private void wecom() {
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()).set(EnterpriseIntegrationRow::getProviderCode, "wecom_aibot")
            .set(EnterpriseIntegrationRow::getIdentityScope, "bot").set(EnterpriseIntegrationRow::getExternalTenantId, null));
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getStatus, "revoked")
            .set(UserChannelBindingRow::getRevokedAt, Instant.now()));
    }

    private JsonNode wecomMessage(String id, String code, boolean direct) {
        var event = json.createObjectNode().put("cmd", "aibot_msg_callback");
        event.putObject("headers").put("req_id", id);
        var body = event.putObject("body").put("msgid", id).put("aibotid", bots.application(bot).externalAppId())
            .put("chattype", direct ? "single" : "group").put("chatid", externalChat).put("msgtype", "text");
        body.putObject("from").put("userid", "test-user");
        body.putObject("text").put("content", "绑定 " + code);
        return event;
    }

    private String pairingCode() throws Exception {
        var initial = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var login = mvc.perform(post("/api/v1/auth/login").cookie(initial.getResponse().getCookie("SESSION"))
            .header("X-CSRF-Token", data(initial).path("token").asText()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identifier", initiator.username(), "password", "chat-isolated-user-password"))))
            .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("SESSION");
        var token = data(mvc.perform(get("/api/v1/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("token").asText();
        return data(mvc.perform(post(base() + "/chat/bindings/pairing").cookie(cookie).header("X-CSRF-Token", token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("connectionId", bot.getConnectionId()))))
            .andExpect(status().isOk()).andReturn()).path("code").asText();
    }

    private long activeBindings() {
        return databaseAccess.mapper(UserChannelBindingMapper.class).selectCount(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getConnectionId, bot.getConnectionId())
            .eq(UserChannelBindingRow::getStatus, "active"));
    }
}
