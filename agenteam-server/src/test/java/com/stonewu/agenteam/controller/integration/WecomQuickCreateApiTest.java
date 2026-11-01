package com.stonewu.agenteam.controller.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.chat.ChatRoutingService;
import com.stonewu.agenteam.service.chat.ChatReplyDeliveryPolicy;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.integration.WecomSetupJobMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatIngressService;
import com.stonewu.agenteam.service.chat.ChatNormalizationTransactions;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.wecom.WecomEnterpriseConnections;
import com.stonewu.agenteam.service.integration.wecom.WecomEnterpriseVerifier;
import com.stonewu.agenteam.service.integration.wecom.WecomMemberAliases;
import com.stonewu.agenteam.service.integration.wecom.WecomSetupProcessor;
import com.stonewu.agenteam.service.integration.wecom.WecomSetupStore;
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
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用隔离数据库和模拟企微接口检查完整创建、一次绑定、跨企业拒绝和失效后的访问限制。 */
@Import({SharedEnterpriseTestEdition.class, WecomQuickCreateApiTest.PlatformConfiguration.class})
class WecomQuickCreateApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    private static final MockChannelServer PLATFORM = platform();
    @Autowired private WecomSetupStore setup;
    @Autowired private WecomSetupProcessor processor;
    @Autowired private WecomSetupJobMapper jobs;
    @Autowired private EnterpriseIntegrationMapper connections;
    @Autowired private ChatBotBindingMapper bots;
    @Autowired private UserChannelBindingMapper bindings;
    @Autowired private WecomEnterpriseConnections enterpriseLinks;
    @Autowired private WecomEnterpriseVerifier verifier;
    @Autowired private WecomMemberAliases aliases;
    @Autowired private ChatIngressService ingress;
    @Autowired private ChatNormalizationTransactions normalization;
    @Autowired private ChatIdentityService identities;
    @Autowired private ChatPayloadCipher chatCipher;
    @Autowired private ChatRoutingService routing;
    @Autowired private ChatReplyDeliveryPolicy delivery;
    @Autowired private ChatOutboundService outbound;

    @TestConfiguration
    static class PlatformConfiguration {
        @Bean @Primary
        IntegrationEndpoints integrationEndpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("agenteam.wecom-setup.worker-enabled", () -> false);
        registry.add("agenteam.feishu-setup.worker-enabled", () -> false);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        PLATFORM.close();
        ENV.close();
    }

    @Test
    void oneEnterpriseBindingWorksForTwoBotsAndRevocationImmediatelyStopsBoth() throws Exception {
        String parent = connectEnterprise();
        var first = createBot(parent, "人事助手 A", true);
        var second = createBot(parent, "人事助手 B", false);
        assertEquals(parent, enterpriseLinks.verified(enterprise, first.getConnectionId()).orElseThrow().enterprise().getId());
        assertEquals(parent, enterpriseLinks.verified(enterprise, second.getConnectionId()).orElseThrow().enterprise().getId());
        var pairing = data(write(base() + "/chat/bindings/pairing", Map.of("connectionId", parent), key()).andExpect(status().isOk()).andReturn());
        assertTrue(pairing.path("command").asText().startsWith("绑定企业 "));
        reply(Map.of("errcode", 0, "userid_list", List.of(), "invalid_open_userid_list", List.of("member-a")));
        member("member-a");
        receive(first, "member-a", pairing.path("command").asText());
        var rows = bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise));
        assertEquals(1, rows.size());
        var binding = rows.getFirst();
        assertEquals(parent, binding.getConnectionId());
        assertEquals("wecom_userid", binding.getExternalSubjectType());
        assertEquals("member-a", binding.getExternalSubjectId());
        assertTrue(binding.getChatEnabled());

        // 第二只机器人收到加密编号，也必须找到第一只机器人建立的那一条企业绑定。
        converted("encrypted-member-a", "member-a");
        member("member-a");
        receive(second, "encrypted-member-a", "开始工作");
        assertEquals(binding.getId(), identities.find(enterprise, first.getConnectionId(), "wecom_aibot_userid", "member-a").orElseThrow().getId());
        assertEquals(binding.getId(), identities.find(enterprise, second.getConnectionId(), "wecom_aibot_userid", "encrypted-member-a").orElseThrow().getId());
        assertTrue(identities.find("another-enterprise", second.getConnectionId(), "wecom_aibot_userid", "encrypted-member-a").isEmpty());
        assertEquals(1, bindings.selectCount(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)));

        change(HttpMethod.PATCH, base() + "/me/channels/" + binding.getId(),
            Map.of("receiveEnabled", false, "externalLoginEnabled", false, "chatEnabled", false), "1", key()).andExpect(status().isOk());
        assertTrue(identities.find(enterprise, first.getConnectionId(), "wecom_aibot_userid", "member-a").isEmpty());
        assertTrue(identities.find(enterprise, second.getConnectionId(), "wecom_aibot_userid", "encrypted-member-a").isEmpty());
        change(HttpMethod.PATCH, base() + "/me/channels/" + binding.getId(),
            Map.of("receiveEnabled", false, "externalLoginEnabled", false, "chatEnabled", true), "2", key()).andExpect(status().isOk());
        change(HttpMethod.DELETE, base() + "/me/channels/" + binding.getId(), null, "3", key()).andExpect(status().isOk());
        assertTrue(identities.find(enterprise, second.getConnectionId(), "wecom_aibot_userid", "encrypted-member-a").isEmpty());

        connections.update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, parent)
            .set(EnterpriseIntegrationRow::getCredentialRevision, 2L));
        assertTrue(enterpriseLinks.verified(enterprise, first.getConnectionId()).isEmpty());
        assertTrue(enterpriseLinks.verified(enterprise, second.getConnectionId()).isEmpty());
        identity(first.getPlatformBotId());
        token();
        converted("encrypted-owner", "owner");
        member("owner");
        assertTrue(verifier.ensure(enterprise, first.getConnectionId()).isPresent());
        assertTrue(enterpriseLinks.verified(enterprise, second.getConnectionId()).isEmpty());
        assertTrue(identities.find(enterprise, first.getConnectionId(), "wecom_userid", "member-a").isEmpty());
        drain();
    }

    @Test
    void unmatchedEncryptedOwnerNeverCreatesAConnectionAndRetryKeepsTheSameBotCredentials() throws Exception {
        String parent = connectEnterprise();
        String job = start(parent, "企业核验助手");
        String bot = "aib_" + key().replace("-", "");
        scan(job, bot);
        identity(bot);
        token();
        reply(Map.of("errcode", 0, "userid_list", List.of(), "invalid_open_userid_list", List.of("encrypted-owner")));
        step(job);
        var failed = jobs.selectById(job);
        assertEquals("failed", failed.getStage());
        assertEquals("verifying", failed.getResumeStage());
        assertEquals("企业核验未通过，请检查后重试。", failed.getErrorSummary());
        assertNull(failed.getConnectionId());
        assertNotNull(failed.getEncryptedJson());
        assertFalse(failed.getEncryptedJson().contains("private-bot-secret"));
        assertEquals(0, connections.selectCount(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getProviderCode, "wecom_aibot")));
        write(path() + "/jobs/" + job + "/retry", Map.of("expectedRevision", failed.getRevision()), key()).andExpect(status().isOk());
        identity(bot);
        converted("encrypted-owner", "owner");
        member("owner");
        step(job);
        step(job);
        assertEquals("completed", jobs.selectById(job).getStage());
        assertNull(jobs.selectById(job).getEncryptedJson());
        drain();
    }

    @Test
    void unfinishedAndInstalledBotsReserveTheirNamesUntilCancelled() throws Exception {
        String parent = connectEnterprise();
        String job = start(parent, "验收机器人 A");
        var input = Map.of("enterpriseConnectionId", parent, "agentId", agent, "name", "验收机器人 a", "versionPolicy", "latest_on_new_task");
        write(path() + "/jobs", input, key()).andExpect(status().isUnprocessableEntity());
        write(path() + "/jobs/" + job + "/cancel", Map.of("expectedRevision", jobs.selectById(job).getRevision()), key())
            .andExpect(status().isOk());
        createBot(parent, "验收机器人 A", true);
        write(path() + "/jobs", input, key()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void enterprisePairingCodesRequirePrivateChatAndExpireAfterUseOrFailedAttempts() throws Exception {
        String parent = connectEnterprise();
        var bot = createBot(parent, "绑定指令检查", true);
        var flows = databaseAccess.mapper(ChannelOauthSessionMapper.class);
        var first = pairing(parent);
        String code = first.path("code").asText();
        String wrong = "绑定企业 " + code.substring(0, code.indexOf('.') + 1) + "incorrect";
        converted("encrypted-member", "member");
        member("member");
        for (int index = 0; index < 5; index++) {
            receive(bot, "encrypted-member", wrong);
        }
        var failed = flows.selectById(code.substring(0, code.indexOf('.')));
        assertEquals("failed", failed.getStatus());
        assertEquals(5, failed.getPairingAttempts());
        receive(bot, "encrypted-member", first.path("command").asText());
        assertEquals(0, bindings.selectCount(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)));

        var expired = pairing(parent);
        String expiredCode = expired.path("code").asText();
        flows.update(new LambdaUpdateWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, enterprise)
            .eq(ChannelOauthSessionRow::getId, expiredCode.substring(0, expiredCode.indexOf('.')))
            .set(ChannelOauthSessionRow::getCreatedAt, Instant.now().minusSeconds(600))
            .set(ChannelOauthSessionRow::getExpiresAt, Instant.now().minusSeconds(1)));
        receive(bot, "encrypted-member", expired.path("command").asText());
        assertEquals(0, bindings.selectCount(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)));

        var valid = pairing(parent);
        receive(bot, "encrypted-member", valid.path("command").asText(), false);
        assertEquals(0, bindings.selectCount(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)));
        String receiptId = receive(bot, "encrypted-member", valid.path("command").asText());
        receive(bot, "encrypted-member", valid.path("command").asText());
        assertEquals(1, bindings.selectCount(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)));
        var receipt = databaseAccess.mapper(ChatInboundReceiptMapper.class).selectById(receiptId);
        assertNotNull(receipt.getPayloadPurgedAt());
        assertFalse(chatCipher.decrypt(enterprise, "receipt", receiptId, receipt.getPayloadEncrypted()).toString().contains(valid.path("code").asText()));
    }

    @Test
    void commandsKeepTheCurrentConversationAndHistoryCanReturnToAnOlderOne() throws Exception {
        String parent = connectEnterprise();
        var bot = createBot(parent, "会话助手", true);
        converted("encrypted-member", "member");
        member("member");
        receive(bot, "encrypted-member", pairing(parent).path("command").asText());
        String first = receive(bot, "encrypted-member", "记住我的主题是排序", false);
        routeReceipt(first);
        var groups = databaseAccess.mapper(ChatConversationMapper.class);
        var group = groups.selectOne(new LambdaQueryWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, enterprise)
            .eq(ChatConversationRow::getConversationKind, "group"));
        assertEquals("enabled", group.getStatus());
        assertEquals("full_allowed", group.getResultPolicy());
        assertEquals(bot.getId(), group.getIngressBotId());
        var tasks = databaseAccess.mapper(CollaborationTaskMapper.class);
        var original = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise));
        String second = receive(bot, "encrypted-member", "我刚才说的主题是什么", false);
        routeReceipt(second);
        assertEquals(1, tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)));
        assertEquals(2L, tasks.selectById(original.getId()).getInputRevision());
        var outgoing = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        outbound.progress(enterprise, group.getId(), bot.getId(), original.getId(), null, "test-start", "started", "开始处理", null);
        assertEquals(0, outgoing.selectCount(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getTaskId, original.getId())
            .in(ChatOutboundMessageRow::getKind, "accepted", "input_added", "started")));
        routeReceipt(receive(bot, "encrypted-member", "/new@会话助手 ", false));
        assertEquals(1, tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)));
        assertEquals(2L, tasks.selectById(original.getId()).getInputRevision());
        routeReceipt(receive(bot, "encrypted-member", "现在聊天气", false));
        var all = tasks.selectList(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise));
        assertEquals(2, all.size());
        var newer = all.stream().filter(value -> !value.getId().equals(original.getId())).findFirst().orElseThrow();
        assertEquals(1L, newer.getInputRevision());
        assertNotEquals("cancelled", tasks.selectById(original.getId()).getStatus());
        routeReceipt(receive(bot, "encrypted-member", "明天呢", false));
        assertEquals(2L, tasks.selectById(newer.getId()).getInputRevision());
        routeReceipt(receive(bot, "encrypted-member", "@会话助手 /history", false));
        assertEquals(2, tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)));
        var card = outgoing.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
            .eq(ChatOutboundMessageRow::getKind, "conversation_history"));
        assertNull(card.getTaskId());
        var rendered = delivery.prepare(card, chatCipher.decrypt(enterprise, "outbound", card.getId(), card.getPayloadEncrypted()));
        assertEquals(2, rendered.path("historyChoices").size());
        String action = null;
        for (var choice : rendered.path("historyChoices")) {
            if (original.getId().equals(choice.path("taskId").asText())) {
                action = choice.path("key").asText();
            }
        }
        assertNotNull(action);
        outgoing.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, card.getId())
            .set(ChatOutboundMessageRow::getStatus, "sent")
            .set(ChatOutboundMessageRow::getRenderedPayloadEncrypted, chatCipher.encrypt(enterprise, "outbound-rendered", card.getId(), rendered)));
        // 转发列表到另一个群、提交列表之外的选项均不能切换会话。
        cardEvent(bot, card.getId(), action, "another-group");
        cardEvent(bot, card.getId(), "history_choose_3", "test-group");
        routeReceipt(receive(bot, "encrypted-member", "后天呢", false));
        assertEquals(3L, tasks.selectById(newer.getId()).getInputRevision());
        cardEvent(bot, card.getId(), action, "test-group");
        routeReceipt(receive(bot, "encrypted-member", "回到排序问题", false));
        assertEquals(3L, tasks.selectById(original.getId()).getInputRevision());
        routeReceipt(receive(bot, "encrypted-member", "@会话助手 /new 分析新问题", false));
        assertEquals(3, tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)));
        var withQuestion = tasks.selectList(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise))
            .stream().filter(value -> !all.stream().map(CollaborationTaskRow::getId).toList().contains(value.getId())).findFirst().orElseThrow();
        assertEquals("分析新问题", chatCipher.decrypt(enterprise, "task-objective", withQuestion.getId(), withQuestion.getObjectiveEncrypted()).path("text").asText());

        // 手工停用不能被后续提及自动恢复。
        groups.update(new LambdaUpdateWrapper<ChatConversationRow>().eq(ChatConversationRow::getId, group.getId())
            .set(ChatConversationRow::getStatus, "disabled").setIncrBy(ChatConversationRow::getRevision, 1));
        var ignored = receive(bot, "encrypted-member", "不要自动恢复", false);
        assertEquals("ignored", databaseAccess.mapper(ChatInboundReceiptMapper.class).selectById(ignored).getStatus());
        assertEquals("disabled", groups.selectById(group.getId()).getStatus());
    }

    @Test
    void textAnswersKeepEveryPartAndRespectChangedResultPermissions() throws Exception {
        String parent = connectEnterprise();
        var bot = createBot(parent, "回复助手", true);
        converted("encrypted-member", "member");
        member("member");
        receive(bot, "encrypted-member", pairing(parent).path("command").asText());
        routeReceipt(receive(bot, "encrypted-member", "请给出完整结果", false));
        var tasks = databaseAccess.mapper(CollaborationTaskMapper.class);
        var task = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise));
        String answer = "需要保留的完整内容🙂".repeat(260) + "结束。";
        outbound.text(enterprise, task.getConversationId(), bot.getId(), task.getId(), null, "long-answer", "completed", answer, null);
        var outgoing = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        var parts = outgoing.selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
            .eq(ChatOutboundMessageRow::getTaskId, task.getId()).eq(ChatOutboundMessageRow::getKind, "completed")
            .orderByAsc(ChatOutboundMessageRow::getPartNo));
        assertTrue(parts.size() > 1);
        var restored = new StringBuilder();
        for (var part : parts) {
            var payload = delivery.prepare(part, chatCipher.decrypt(enterprise, "outbound", part.getId(), part.getPayloadEncrypted()));
            assertFalse(payload.has("conversationCardId"));
            assertTrue(payload.path("replyFull").asBoolean());
            restored.append(payload.at("/content/text").asText());
        }
        assertEquals(answer, restored.toString());
        var question = outbound.text(enterprise, task.getConversationId(), bot.getId(), task.getId(), null,
            "question", "waiting_input", "请补充月份。", null);
        var questionPayload = delivery.prepare(question, chatCipher.decrypt(enterprise, "outbound", question.getId(), question.getPayloadEncrypted()));
        assertEquals("请补充月份。", questionPayload.at("/content/text").asText());
        assertFalse(questionPayload.has("conversationCardId"));

        var first = parts.getFirst();
        var original = chatCipher.decrypt(enterprise, "outbound", first.getId(), first.getPayloadEncrypted());
        var submitted = delivery.prepare(first, original);
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, task.getConversationId())
            .set(ChatConversationRow::getResultPolicy, "summary_link").setIncrBy(ChatConversationRow::getRevision, 1));
        var restricted = delivery.prepare(first, original);
        assertFalse(restricted.path("replyFull").asBoolean());
        assertFalse(restricted.at("/content/text").asText().contains("需要保留"));
        assertTrue(restricted.at("/content/text").asText().contains("[查看结果]"));
        var second = parts.get(1);
        assertEquals("CHAT_REPLY_MODE_SKIP", assertThrows(ApiException.class, () -> delivery.prepare(second,
            chatCipher.decrypt(enterprise, "outbound", second.getId(), second.getPayloadEncrypted()))).code());
        first.setFirstSubmittedAt(Instant.now());
        assertEquals("CHAT_SEND_UNKNOWN", assertThrows(ApiException.class, () -> delivery.prepare(first, submitted)).code());
    }

    @Test
    void staleEnterpriseProofDoesNotAutomaticallyEnableAGroup() throws Exception {
        String parent = connectEnterprise();
        var bot = createBot(parent, "核验助手", true);
        connections.update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, parent)
            .setIncrBy(EnterpriseIntegrationRow::getCredentialRevision, 1));
        String receipt = receive(bot, "member", "第一次提及", false);
        assertEquals("ignored", databaseAccess.mapper(ChatInboundReceiptMapper.class).selectById(receipt).getStatus());
    }

    @Test
    void quotedTextReachesBothNewTasksAndFollowupsWithoutChangingTheTaskReference() throws Exception {
        String parent = connectEnterprise();
        var bot = createBot(parent, "引用助手", true);
        converted("encrypted-member", "member");
        member("member");
        receive(bot, "encrypted-member", pairing(parent).path("command").asText());
        var quote = json.createObjectNode().put("msgtype", "text");
        quote.putObject("text").put("content", "预算3200元。参考旧编号GC-000000000000，新任务只是引用原文。");
        routeReceipt(receive(bot, "encrypted-member", "引用里的预算是多少", false, quote));
        var tasks = databaseAccess.mapper(CollaborationTaskMapper.class);
        var task = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise));
        assertNotNull(task);
        var objective = chatCipher.decrypt(enterprise, "task-objective", task.getId(), task.getObjectiveEncrypted());
        assertTrue(objective.path("text").asText().contains("3200元"));
        assertTrue(objective.path("text").asText().contains("引用消息："));
        quote.withObject("/text").put("content", "预算改为4800元。");
        routeReceipt(receive(bot, "encrypted-member", "新的引用里改成了多少", false, quote));
        var continued = tasks.selectById(task.getId());
        assertEquals(2L, continued.getInputRevision());
        assertEquals(1, tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)));
        assertTrue(chatCipher.decrypt(enterprise, "task-objective", task.getId(), continued.getObjectiveEncrypted())
            .path("additionalRequirements").toString().contains("4800元"));
        assertTrue(data(mvc.perform(get(base() + "/collaboration/tasks/" + task.getId() + "/messages").cookie(cookie))
            .andExpect(status().isOk()).andReturn()).toString().contains("4800元"));
    }

    private void routeReceipt(String id) {
        var receipt = databaseAccess.mapper(ChatInboundReceiptMapper.class).selectById(id);
        assertNotNull(receipt.getMessageId());
        routing.route(enterprise, receipt.getMessageId());
        routing.route(enterprise, receipt.getMessageId());
    }

    private void cardEvent(ChatBotBindingRow bot, String card, String action, String chat) throws Exception {
        var frame = json.createObjectNode().put("cmd", "aibot_event_callback");
        frame.putObject("headers").put("req_id", key());
        var body = frame.putObject("body").put("aibotid", bot.getPlatformBotId()).put("msgid", key())
            .put("chatid", chat).put("msgtype", "event");
        body.putObject("from").put("userid", "encrypted-member");
        body.putObject("event").put("eventtype", "template_card_event")
            .putObject("template_card_event").put("card_type", "button_interaction").put("task_id", card).put("event_key", action);
        String id = ingress.accept(enterprise, bot.getId(), 1L, frame);
        assertEquals(id, ingress.accept(enterprise, bot.getId(), 1L, frame));
        var lease = normalization.claim("card-test").orElseThrow();
        aliases.prepare(lease);
        normalization.normalize(lease);
        assertEquals(id, ingress.accept(enterprise, bot.getId(), 1L, frame));
        assertTrue(normalization.claim("duplicate-card").isEmpty());
    }

    private JsonNode pairing(String parent) throws Exception {
        return data(write(base() + "/chat/bindings/pairing", Map.of("connectionId", parent), key()).andExpect(status().isOk()).andReturn());
    }

    private String connectEnterprise() throws Exception {
        token();
        reply(Map.of("errcode", 0, "agentid", 1000001, "name", "企微企业应用"));
        var input = Map.of("providerCode", "wecom", "purpose", "enterprise", "name", "企业 A", "externalTenantId", "corp-" + enterprise,
            "externalAppId", "1000001", "secret", "private-enterprise-secret", "bindingEnabled", true, "loginEnabled", false, "messagingEnabled", true);
        String requestId = key();
        var created = data(write(base() + "/integrations/quick-wecom", input, requestId).andExpect(status().isCreated()).andReturn());
        assertEquals("enabled", created.path("status").asText());
        assertEquals(created.path("id"), data(write(base() + "/integrations/quick-wecom", input, requestId)
            .andExpect(status().isCreated()).andReturn()).path("id"));
        drain();
        return created.path("id").asText();
    }

    private ChatBotBindingRow createBot(String parent, String name, boolean fetchToken) throws Exception {
        String job = start(parent, name);
        String bot = "aib_" + key().replace("-", "");
        scan(job, bot);
        identity(bot);
        if (fetchToken) token();
        converted("encrypted-owner", "owner");
        member("owner");
        step(job);
        assertEquals("binding", jobs.selectById(job).getStage());
        step(job);
        var completed = jobs.selectById(job);
        assertEquals("completed", completed.getStage());
        assertNull(completed.getEncryptedJson());
        var result = bots.selectById(completed.getBotId());
        assertEquals("enabled", result.getStatus());
        assertEquals(agent, result.getAgentId());
        assertEquals(bot, result.getPlatformBotId());
        bots.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, result.getId())
            .set(ChatBotBindingRow::getLeaseVersion, 1L).set(ChatBotBindingRow::getLeaseUntil, Instant.now().plusSeconds(300)).set(ChatBotBindingRow::getLeaseOwner, "test"));
        return bots.selectById(result.getId());
    }

    private String start(String parent, String name) throws Exception {
        var input = Map.of("enterpriseConnectionId", parent, "agentId", agent, "name", name, "versionPolicy", "latest_on_new_task");
        String key = key();
        String id = data(write(path() + "/jobs", input, key).andExpect(status().isAccepted()).andReturn()).path("id").asText();
        assertEquals(id, data(write(path() + "/jobs", input, key).andExpect(status().isAccepted()).andReturn()).path("id").asText());
        assertThrows(ApiException.class, () -> setup.require(enterprise, "other-user", id));
        return id;
    }

    private void scan(String job, String bot) throws Exception {
        reply(Map.of("data", Map.of("scode", "session-" + job, "auth_url", "https://work.weixin.qq.com/ai/qc/c?s=session-" + job)));
        step(job);
        mvc.perform(get(path() + "/jobs/" + job + "/qr").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get(path() + "/jobs/" + job + "/qr")).andExpect(status().isUnauthorized());
        assertFalse(setup.view(jobs.selectById(job)).toString().contains("session-"));
        reply(Map.of("data", Map.of("status", "success", "bot_info", Map.of("botid", bot, "secret", "private-bot-secret"))));
        step(job);
        assertEquals("verifying", jobs.selectById(job).getStage());
    }

    private void identity(String bot) {
        reply(Map.of("errcode", 0, "token", "private-cli-token"));
        String context = "<extra_identity_context>\n机器人身份：\n名字：平台机器人\nID：" + bot
            + "\n授权真人用户身份：\n名字：测试成员\nID：encrypted-owner\n权限说明不参与身份核验。\n</extra_identity_context>";
        reply(Map.of("errcode", 0, "results_json", json.valueToTree(Map.of("result", json.valueToTree(Map.of("extra_identity_context", context)).toString())).toString()));
    }

    private String receive(ChatBotBindingRow bot, String member, String content) throws Exception {
        return receive(bot, member, content, true);
    }

    private String receive(ChatBotBindingRow bot, String member, String content, boolean direct) throws Exception {
        return receive(bot, member, content, direct, null);
    }

    private String receive(ChatBotBindingRow bot, String member, String content, boolean direct, JsonNode quote) throws Exception {
        var frame = json.createObjectNode().put("cmd", "aibot_msg_callback");
        frame.putObject("headers").put("req_id", "req-" + key());
        var body = frame.putObject("body").put("aibotid", bot.getPlatformBotId()).put("msgid", "message-" + key())
            .put("chattype", direct ? "single" : "group").put("chatid", "test-group").put("msgtype", "text");
        body.putObject("from").put("userid", member);
        body.putObject("text").put("content", content);
        if (quote != null) body.set("quote", quote);
        String receiptId = ingress.accept(enterprise, bot.getId(), 1L, frame);
        if (!direct && "ignored".equals(databaseAccess.mapper(ChatInboundReceiptMapper.class).selectById(receiptId).getStatus())) {
            drain();
            return receiptId;
        }
        var lease = normalization.claim("identity-test").orElseThrow();
        aliases.prepare(lease);
        normalization.normalize(lease);
        drain();
        return receiptId;
    }

    private void step(String id) throws Exception {
        var row = jobs.selectById(id);
        var lease = setup.claim(row, "setup-test");
        assertNotNull(lease);
        assertNull(setup.claim(row, "another-worker"));
        processor.process(lease);
        drain();
    }

    private void token() { reply(Map.of("errcode", 0, "access_token", "private-enterprise-token", "expires_in", 7200)); }
    private void converted(String source, String target) { reply(Map.of("errcode", 0, "userid_list", List.of(Map.of("open_userid", source, "userid", target)), "invalid_open_userid_list", List.of())); }
    private void member(String id) { reply(Map.of("errcode", 0, "userid", id, "status", 1)); }
    private void reply(Object value) { PLATFORM.raw(200, json.valueToTree(value).toString(), Map.of()); }
    private void drain() throws Exception { while (PLATFORM.remainingRequests() > 0) PLATFORM.take(); }
    private String path() { return base() + "/chat/wecom"; }
    private String key() { return UUID.randomUUID().toString(); }
    private static MockChannelServer platform() {
        try { return new MockChannelServer(); } catch (Exception failure) { throw new IllegalStateException("测试企微接口无法启动", failure); }
    }
}
