package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationBotMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Kind;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** 可信入群或首次提及自动配置新群；管理员保存设置后由其明确选择接管。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChatGroupActivationService {
    private static final String AUTOMATIC = "automaticBotSetup";
    private final ChatConversationMapper groups;
    private final ChatConversationBotMapper members;
    private final ChatBotBindingMapper botRows;
    private final ChatBotBindingService bots;
    private final EnterpriseIntegrationMapper applications;
    private final ChatMentionIdentityService mentions;
    private final ChatOutboundService outbound;
    private final ResourceJson json;
    private final Clock clock;

    public ChatGroupActivationService(ChatConversationMapper groups, ChatConversationBotMapper members, ChatBotBindingMapper botRows,
            ChatBotBindingService bots, ChatMentionIdentityService mentions, ChatOutboundService outbound, ResourceJson json, Clock clock,
            EnterpriseIntegrationMapper applications) {
        this.groups = groups;
        this.members = members;
        this.botRows = botRows;
        this.bots = bots;
        this.applications = applications;
        this.mentions = mentions;
        this.outbound = outbound;
        this.json = json;
        this.clock = clock;
    }

    public ChatConversationRow activate(ChatBotBindingRow receiver, ChatConversationRow group, NormalizedChatEvent event) {
        boolean newGroup = "discovered".equals(group.getStatus()) && group.getRevision() == 1;
        if (!"feishu".equals(group.getPlatformCode()) || !"group".equals(group.getConversationKind())
            || !"enabled".equals(receiver.getStatus()) || !Boolean.TRUE.equals(receiver.getChatEnabled())
            || !newGroup && !("enabled".equals(group.getStatus()) && automatic(group))
            || event.kind() != Kind.BOT_JOINED && !mentionsReceiver(receiver, event) && !(automatic(group) && event.humanMessage())) {
            return group;
        }
        if (!bots.currentCredentials(receiver, bots.application(receiver).credentialRevision())) {
            return group;
        }
        if (mentionsReceiver(receiver, event)) {
            // 平台在离群时间之后新投递的本人提及证明机器人已重新入群，不依赖额外订阅入群事件。
            members.update(new LambdaUpdateWrapper<ChatConversationBotRow>()
                .eq(ChatConversationBotRow::getEnterpriseId, group.getEnterpriseId()).eq(ChatConversationBotRow::getConversationId, group.getId())
                .eq(ChatConversationBotRow::getBotBindingId, receiver.getId()).eq(ChatConversationBotRow::getMembershipStatus, "left")
                .lt(ChatConversationBotRow::getVerifiedAt, event.platformCreatedAt())
                .set(ChatConversationBotRow::getMembershipStatus, "present").set(ChatConversationBotRow::getVerifiedAt, event.platformCreatedAt())
                .set(ChatConversationBotRow::getVerificationSource, "verified_message").set(ChatConversationBotRow::getUpdatedAt, clock.instant())
                .setIncrBy(ChatConversationBotRow::getRevision, 1));
        }
        var existing = members.selectList(new LambdaQueryWrapper<ChatConversationBotRow>()
            .eq(ChatConversationBotRow::getEnterpriseId, group.getEnterpriseId()).eq(ChatConversationBotRow::getConversationId, group.getId())
            .eq(ChatConversationBotRow::getMembershipStatus, "present"));
        if (existing.stream().noneMatch(member -> member.getBotBindingId().equals(receiver.getId()))) {
            return group;
        }
        var candidates = botRows.selectList(new LambdaQueryWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getEnterpriseId, group.getEnterpriseId())
            .in(ChatBotBindingRow::getId, existing.stream().map(ChatConversationBotRow::getBotBindingId).toList())
            .eq(ChatBotBindingRow::getStatus, "enabled").eq(ChatBotBindingRow::getChatEnabled, true)
            .orderByAsc(ChatBotBindingRow::getCreatedAt, ChatBotBindingRow::getId));
        if (candidates.isEmpty()) {
            return group;
        }
        var enabledApps = applications.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, group.getEnterpriseId())
            .in(EnterpriseIntegrationRow::getId, candidates.stream().map(ChatBotBindingRow::getConnectionId).distinct().toList())
            .eq(EnterpriseIntegrationRow::getStatus, "enabled")).stream().map(EnterpriseIntegrationRow::getId).toList();
        candidates = candidates.stream().filter(bot -> enabledApps.contains(bot.getConnectionId())).toList();
        if (candidates.isEmpty()) {
            return group;
        }
        var selected = candidates.stream().limit(20).map(ChatBotBindingRow::getId).toList();
        var selectedBots = candidates.stream().filter(bot -> selected.contains(bot.getId())).toList();
        var verified = bots.capabilities(group.getEnterpriseId(), selectedBots, "crossBotIdentity");
        var verifiedBots = selectedBots.stream().filter(bot -> Boolean.TRUE.equals(verified.get(bot.getId()))).toList();
        boolean compatible = !verifiedBots.isEmpty() && mentions.supportsGroup(verifiedBots);
        // 已使用企业消息编号的群不能在凭据变化后退回应用范围，否则旧消息可能再次建立任务。
        boolean multiple = "multi_bot".equals(group.getIngressMode()) || verifiedBots.size() > 1 && compatible;
        String entry = selected.contains(group.getIngressBotId()) ? group.getIngressBotId() : selected.getFirst();
        String mode = multiple ? "multi_bot" : "single_bot";
        if (newGroup || !automatic(group) || !mode.equals(group.getIngressMode()) || !entry.equals(group.getIngressBotId())
            || !"full_allowed".equals(group.getResultPolicy())) {
            var settings = (ObjectNode) json.read(group.getPolicyJson());
            settings.put(AUTOMATIC, true);
            int changed = groups.update(new LambdaUpdateWrapper<ChatConversationRow>()
                .eq(ChatConversationRow::getEnterpriseId, group.getEnterpriseId()).eq(ChatConversationRow::getId, group.getId())
                .eq(ChatConversationRow::getRevision, group.getRevision()).eq(ChatConversationRow::getStatus, group.getStatus())
                .set(ChatConversationRow::getStatus, "enabled").set(ChatConversationRow::getIngressMode, mode)
                .set(ChatConversationRow::getIngressBotId, entry).set(ChatConversationRow::getDefaultBotId, entry)
                .set(ChatConversationRow::getResultPolicy, "full_allowed")
                .set(ChatConversationRow::getPolicyJson, json.write(settings)).set(ChatConversationRow::getUpdatedAt, clock.instant())
                .setIncrBy(ChatConversationRow::getRevision, 1));
            if (changed != 1) {
                throw ApiException.versionConflict(group.getRevision());
            }
            group.setStatus("enabled");
            group.setIngressMode(mode);
            group.setIngressBotId(entry);
            group.setDefaultBotId(entry);
            group.setResultPolicy("full_allowed");
            group.setPolicyJson(json.write(settings));
            group.setRevision(group.getRevision() + 1);
        }
        for (var member : existing) {
            boolean allowed = selected.contains(member.getBotBindingId());
            boolean receive = allowed && (multiple ? compatible && Boolean.TRUE.equals(verified.get(member.getBotBindingId()))
                : entry.equals(member.getBotBindingId()));
            if (Objects.equals(member.getReceiveEnabled(), receive) && Objects.equals(member.getSendEnabled(), allowed)
                && Objects.equals(member.getCollaborationEnabled(), allowed)) {
                continue;
            }
            int changed = members.update(new LambdaUpdateWrapper<ChatConversationBotRow>()
                .eq(ChatConversationBotRow::getEnterpriseId, group.getEnterpriseId()).eq(ChatConversationBotRow::getId, member.getId())
                .eq(ChatConversationBotRow::getRevision, member.getRevision()).eq(ChatConversationBotRow::getMembershipStatus, "present")
                .set(ChatConversationBotRow::getReceiveEnabled, receive).set(ChatConversationBotRow::getSendEnabled, allowed)
                .set(ChatConversationBotRow::getCollaborationEnabled, allowed).set(ChatConversationBotRow::getUpdatedAt, clock.instant())
                .setIncrBy(ChatConversationBotRow::getRevision, 1));
            if (changed != 1) {
                throw ApiException.versionConflict(member.getRevision());
            }
        }
        return group;
    }

    public boolean automatic(ChatConversationRow group) {
        return json.read(group.getPolicyJson()).path(AUTOMATIC).asBoolean();
    }

    public void guideReceiver(ChatBotBindingRow receiver, ChatConversationRow group, NormalizedChatEvent event) {
        if (!automatic(group) || !"enabled".equals(group.getStatus())
            || receiver.getId().equals(group.getIngressBotId()) || !mentionsReceiver(receiver, event)) {
            return;
        }
        var entry = bots.require(group.getEnterpriseId(), group.getIngressBotId());
        outbound.text(group.getEnterpriseId(), group.getId(), receiver.getId(), null, null, "group-entry:" + event.messageId(),
            "guidance", "本群目前由“" + entry.getRoleName() + "”接收任务，请 @ 该机器人继续。", event.messageId());
    }

    public static Map<String, Object> visibleLimits(ResourceJson json, ChatConversationRow group) {
        var settings = (ObjectNode) json.read(group.getPolicyJson());
        settings.remove(AUTOMATIC);
        return json.object(settings);
    }

    private boolean mentionsReceiver(ChatBotBindingRow bot, NormalizedChatEvent event) {
        return event.humanMessage() && event.mentions().stream().anyMatch(mention -> bot.getPlatformBotId().equals(mention.externalId()));
    }
}
