package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.chat.request.ChatCheckRequest;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationSecrets;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Comparator;

/** 连接成功不等于业务能力通过；每项检查保存独立的真实证据。 */
@Service
public class ChatCapabilityService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatCapabilityService.class);
    private static final Pattern RECEIVE_MARKER = Pattern.compile(
        "群策能力验证-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}(?![a-f0-9-])");
    public record Check(String enterprise, String botId, long revision, long credentialRevision, String capability, String status, String errorCode) {
    }
    private final ChatBotBindingService bots;
    private final ChatBotBindingMapper rows;
    private final ChatConversationService conversations;
    private final UserChannelBindingMapper bindings;
    private final ChatPlatformRegistry platforms;
    private final ChatOutboundService outbound;
    private final ChannelAuthorizationSecrets secrets;
    private final ResourceJson json;
    private final Clock clock;
    private final EnterpriseMapper enterprises;
    private final ChatIdentityService identities;
    private final ChatMentionIdentityService mentions;

    public ChatCapabilityService(ChatBotBindingService bots, ChatBotBindingMapper rows, ChatConversationService conversations,
            UserChannelBindingMapper bindings, ChatPlatformRegistry platforms, ChatOutboundService outbound,
            ChannelAuthorizationSecrets secrets, ResourceJson json, Clock clock, EnterpriseMapper enterprises, ChatIdentityService identities,
            ChatMentionIdentityService mentions) {
        this.bots = bots;
        this.rows = rows;
        this.conversations = conversations;
        this.bindings = bindings;
        this.platforms = platforms;
        this.outbound = outbound;
        this.secrets = secrets;
        this.json = json;
        this.clock = clock;
        this.enterprises = enterprises;
        this.identities = identities;
        this.mentions = mentions;
    }

    public Check prepare(String enterprise, String botId, ChatCheckRequest input) {
        var bot = current(enterprise, botId, input.expectedRevision());
        var app = bots.application(bot);
        var adapter = platforms.adapter(app.providerCode());
        String chatId = null;
        if ("readHistory".equals(input.type())) {
            var group = conversations.require(enterprise, input.conversationId());
            var member = conversations.member(enterprise, group.getId(), botId);
            if (member == null || !member.getMembershipStatus().equals("present") || !group.getStatus().equals("enabled")) {
                throw ChatPlatformRegistry.unavailable();
            }
            chatId = member.getExternalChatId();
        } else if (!"credential".equals(input.type())) {
            throw ApiException.invalidField("type", "请在“测试收发消息”中选择接收测试或发送测试。");
        }
        try {
            if ("credential".equals(input.type())) {
                if (!adapter.identity(app).id().equals(bot.getPlatformBotId())) {
                    return new Check(enterprise, botId, bot.getRevision(), app.credentialRevision(), input.type(), "failed", "CHAT_BOT_IDENTITY_CHANGED");
                }
            } else {
                adapter.checkHistory(app, chatId);
            }
        } catch (ChannelProviderException failure) {
            LOG.warn("机器人能力检查失败，企业={}，机器人={}，能力={}，错误代码={}", enterprise, botId, input.type(), failure.code(), failure);
            return new Check(enterprise, botId, bot.getRevision(), app.credentialRevision(), input.type(), "failed", failure.code());
        }
        return new Check(enterprise, botId, bot.getRevision(), app.credentialRevision(), input.type(), "passed", "");
    }

    @Transactional
    public Map<String, Object> checked(Check check) {
        var bot = current(check.enterprise(), check.botId(), check.revision());
        if (bots.application(bot).credentialRevision() != check.credentialRevision()) {
            throw ApiException.versionConflict(bot.getRevision());
        }
        evidence(bot, check.capability(), Map.of("status", check.status(), "checkedAt", clock.instant().toString(),
            "credentialRevision", check.credentialRevision(), "bindingRevision", check.revision(), "errorCode", check.errorCode()));
        return Map.of("status", check.status(), "type", check.capability(), "errorCode", check.errorCode());
    }

    @Transactional
    public Map<String, Object> receive(UserEntity actor, String enterprise, String botId, ChatCheckRequest input) {
        String marker = "群策能力验证-" + UUID.randomUUID();
        var result = receive(actor, enterprise, botId, input, marker);
        if (input.peerBotId() != null) {
            var bot = bots.require(enterprise, botId);
            var peer = bots.require(enterprise, input.peerBotId());
            var app = bots.application(bot);
            var other = bots.application(peer);
            if (botId.equals(peer.getId()) || !app.providerCode().equals("feishu") || !other.providerCode().equals("feishu")
                || app.externalTenantId() == null || !app.externalTenantId().equals(other.externalTenantId())) {
                throw ApiException.invalidField("peerBotId", "请选择同一飞书企业的两个机器人，再选择已绑定飞书账号的成员参与测试。");
            }
            String user = json.read(bot.getConfigJson()).at("/receiveProbe/userId").asText();
            var peerBinding = bindings.selectList(identities.candidates(enterprise, peer.getConnectionId())
                .eq(UserChannelBindingRow::getUserId, user).orderByAsc(UserChannelBindingRow::getId)).stream().findFirst().orElse(null);
            if (peerBinding == null) {
                throw ApiException.invalidField("senderBindingId", "尚不能确认测试成员在另一机器人中的身份，请检查飞书账号绑定。");
            }
            receive(actor, enterprise, peer.getId(), new ChatCheckRequest(peer.getRevision(), input.conversationId(), input.type(), peerBinding.getId(), botId), marker);
        }
        return result;
    }

    private Map<String, Object> receive(UserEntity actor, String enterprise, String botId, ChatCheckRequest input, String marker) {
        var bot = current(enterprise, botId, input.expectedRevision());
        var group = conversations.require(enterprise, input.conversationId());
        var member = conversations.member(enterprise, group.getId(), botId);
        if (member == null || !member.getMembershipStatus().equals("present") || !group.getStatus().equals("enabled") || !bot.getStatus().equals("enabled")) {
            throw ChatPlatformRegistry.unavailable();
        }
        var query = identities.candidates(enterprise, bot.getConnectionId());
        if (input.senderBindingId() == null) {
            query.eq(UserChannelBindingRow::getUserId, actor.id());
        } else {
            query.eq(UserChannelBindingRow::getId, input.senderBindingId());
        }
        var binding = bindings.selectList(query.orderByAsc(UserChannelBindingRow::getId)).stream().findFirst().orElse(null);
        if (binding == null) {
            throw ApiException.invalidField("senderBindingId", "请参与测试的成员在“个人设置 → 企业账号绑定”绑定账号，并开启“允许通过此账号使用智能体”。");
        }
        var config = (ObjectNode) json.read(bot.getConfigJson());
        config.remove("receiveProof");
        config.set("receiveProbe", json.tree(Map.of("hash", secrets.hash(marker), "expiresAt", clock.instant().plusSeconds(300).toString(),
            "conversation", group.getExternalConversationId(), "sender", binding.getExternalSubjectId(),
            "bindingRevision", bot.getRevision(), "credentialRevision", bots.application(bot).credentialRevision(),
            "userId", binding.getUserId(), "peerBotId", input.peerBotId() == null ? "" : input.peerBotId())));
        changed(rows.update(scope(bot).set(ChatBotBindingRow::getConfigJson, json.write(config))));
        evidence(bot, "receiveMention", Map.of("status", "not_tested", "checkedAt", clock.instant().toString()));
        if (input.peerBotId() != null) {
            evidence(bot, "crossBotIdentity", Map.of("status", "not_tested", "checkedAt", clock.instant().toString()));
        }
        return Map.of("marker", marker, "expiresAt", clock.instant().plusSeconds(300).toString());
    }

    @Transactional
    public boolean consume(ChatBotBindingRow bot, NormalizedChatEvent event) {
        if (!event.humanMessage() || !event.text().contains("群策能力验证-")) {
            return false;
        }
        var matcher = RECEIVE_MARKER.matcher(event.text());
        if (!matcher.find()) {
            return false;
        }
        // 只拦截接收检查生成的完整标记，避免吞掉包含相同文字的普通业务消息。
        var config = (ObjectNode) json.read(bot.getConfigJson());
        var probe = config.path("receiveProbe");
        if (probe.isObject() && probe.path("bindingRevision").asLong() == bot.getRevision()
            && probe.path("credentialRevision").asLong() == bots.application(bot).credentialRevision()
            && Instant.parse(probe.path("expiresAt").asText()).isAfter(clock.instant())
            && identities.find(bot.getEnterpriseId(), bot.getConnectionId(), event.subjectType(), event.senderId(),
                event.metadata().path("senderUnionId").asText(null)).filter(binding -> binding.getUserId().equals(probe.path("userId").asText())).isPresent()
            && event.externalChatId().equals(probe.path("conversation").asText())
            && (event.chatKind().equals("direct") || event.mentions().stream().anyMatch(mention -> mention.externalId().equals(bot.getPlatformBotId())))) {
            if (secrets.matches(matcher.group(), probe.path("hash").asText())) {
                evidence(bot, "receiveMention", Map.of("status", "passed", "checkedAt", clock.instant().toString(),
                    "eventId", event.deliveryId(), "messageId", event.messageId(), "conversation", event.externalChatId(),
                    "bindingRevision", bot.getRevision(), "credentialRevision", bots.application(bot).credentialRevision()));
                var proof = json.tree(Map.of("hash", probe.path("hash").asText(), "userId", probe.path("userId").asText(),
                    "peerBotId", probe.path("peerBotId").asText(), "messageId", event.messageId(), "conversation", event.externalChatId(),
                    "mentions", event.mentions().stream().map(NormalizedChatEvent.Mention::externalId).distinct().sorted().toList(),
                    "expiresAt", probe.path("expiresAt").asText(), "credentialRevision", bots.application(bot).credentialRevision(),
                    "textHash", json.hash(json.tree(event.text()))));
                ((ObjectNode) proof).put("bindingRevision", bot.getRevision());
                ((ObjectNode) proof).set("mentionDetails", json.tree(event.mentions()));
                config.set("receiveProof", proof);
                config.remove("receiveProbe");
                changed(rows.update(scope(bot).set(ChatBotBindingRow::getConfigJson, json.write(config))));
                verifyTogether(bot, proof);
            }
        }
        return true;
    }

    private void verifyTogether(ChatBotBindingRow bot, JsonNode proof) {
        String otherId = proof.path("peerBotId").asText();
        if (otherId.isBlank()) {
            return;
        }
        var peer = bots.require(bot.getEnterpriseId(), otherId);
        var other = json.read(peer.getConfigJson()).path("receiveProof");
        if (!other.isObject() || !proof.path("hash").equals(other.path("hash")) || !bot.getId().equals(other.path("peerBotId").asText())) {
            return;
        }
        var app = bots.application(bot);
        var peerApp = bots.application(peer);
        boolean passed = app.providerCode().equals("feishu") && app.externalTenantId() != null && app.externalTenantId().equals(peerApp.externalTenantId())
            && proof.path("messageId").equals(other.path("messageId")) && proof.path("conversation").equals(other.path("conversation"))
            && proof.path("userId").equals(other.path("userId"))
            && proof.path("textHash").equals(other.path("textHash"))
            && Instant.parse(other.path("expiresAt").asText()).isAfter(clock.instant())
            && proof.path("bindingRevision").asLong() == bot.getRevision() && other.path("bindingRevision").asLong() == peer.getRevision()
            && proof.path("credentialRevision").asLong() == app.credentialRevision() && other.path("credentialRevision").asLong() == peerApp.credentialRevision();
        var pair = mentions.match(bot, proof, peer, other);
        passed &= pair != null;
        if (passed) {
            mentions.save(bot, peer, pair, proof.path("messageId").asText());
        }
        var result = Map.<String, Object>of("status", passed ? "passed" : "failed", "checkedAt", clock.instant().toString(),
            "messageId", proof.path("messageId").asText(), "peerBotId", peer.getId());
        evidence(bot, "crossBotIdentity", result);
        evidence(peer, "crossBotIdentity", Map.of("status", passed ? "passed" : "failed", "checkedAt", clock.instant().toString(),
            "messageId", other.path("messageId").asText(), "peerBotId", bot.getId()));
    }

    @Transactional
    public Map<String, String> send(String enterprise, String botId, ChatCheckRequest input, String operationId) {
        var bot = current(enterprise, botId, input.expectedRevision());
        if ("card".equals(input.type()) && "feishu".equals(bots.application(bot).providerCode())) {
            evidence(bot, "cardInteraction", Map.of("status", "not_tested", "checkedAt", clock.instant().toString()));
            var row = outbound.cardProbe(enterprise, input.conversationId(), botId, operationId);
            return Map.of("outboundId", row.getId(), "status", row.getStatus());
        }
        if ("file".equals(input.type())) {
            evidence(bot, "uploadFile", Map.of("status", "not_tested", "checkedAt", clock.instant().toString()));
            var row = outbound.file(enterprise, input.conversationId(), botId, null, null, "file-probe:" + operationId, null);
            return Map.of("outboundId", row.getId(), "status", row.getStatus());
        }
        if (!"text".equals(input.type())) {
            throw ApiException.invalidField("type", "请选择要发送的测试消息。");
        }
        evidence(bot, "sendText", Map.of("status", "not_tested", "checkedAt", clock.instant().toString()));
        var row = outbound.text(enterprise, input.conversationId(), botId, null, null, "probe:" + operationId, "send_probe",
            "群策连接测试：这是一条测试消息，无需回复。", null);
        return Map.of("outboundId", row.getId(), "status", row.getStatus());
    }

    public void evidence(ChatBotBindingRow bot, String capability, Map<String, Object> evidence) {
        var current = bots.require(bot.getEnterpriseId(), bot.getId());
        var capabilities = (ObjectNode) json.read(current.getCapabilitiesJson());
        var value = (ObjectNode) json.tree(evidence);
        value.put("credentialRevision", bots.application(current).credentialRevision());
        capabilities.set(capability, value);
        changed(rows.update(scope(current).set(ChatBotBindingRow::getCapabilitiesJson, json.write(capabilities))));
    }

    @Transactional
    public void expireProbes() {
        var candidates = rows.selectPage(new Page<ChatBotBindingRow>(1, 100, false), new LambdaQueryWrapper<ChatBotBindingRow>()
            .and(value -> value.like(ChatBotBindingRow::getConfigJson, "receiveProbe").or().like(ChatBotBindingRow::getConfigJson, "receiveProof"))
            .orderByAsc(ChatBotBindingRow::getUpdatedAt, ChatBotBindingRow::getId)).getRecords();
        for (var candidate : candidates.stream().sorted(Comparator.comparing(ChatBotBindingRow::getEnterpriseId)).toList()) {
            enterprises.lockEnterprise(candidate.getEnterpriseId()).orElseThrow();
            var current = rows.selectById(candidate.getId());
            var config = (ObjectNode) json.read(current.getConfigJson());
            boolean removed = false;
            for (String field : List.of("receiveProbe", "receiveProof")) {
                var probe = config.path(field);
                if (probe.isObject() && !Instant.parse(probe.path("expiresAt").asText()).isAfter(clock.instant())) {
                    config.remove(field);
                    removed = true;
                }
            }
            if (removed) {
                changed(rows.update(scope(current).set(ChatBotBindingRow::getConfigJson, json.write(config))));
            }
        }
    }

    private ChatBotBindingRow current(String enterprise, String botId, long revision) {
        var bot = bots.require(enterprise, botId);
        if (bot.getRevision() != revision) {
            throw ApiException.versionConflict(bot.getRevision());
        }
        return bot;
    }

    private LambdaUpdateWrapper<ChatBotBindingRow> scope(ChatBotBindingRow bot) {
        return new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, bot.getEnterpriseId())
            .eq(ChatBotBindingRow::getId, bot.getId()).eq(ChatBotBindingRow::getRevision, bot.getRevision());
    }

    private void changed(int count) {
        if (count != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CONFIGURATION_CHANGED", "机器人配置已变化，请刷新后重试。");
        }
    }
}
