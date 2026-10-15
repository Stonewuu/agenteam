package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Mention;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 仅使用真实联合验证中的提及位置对应机器人，不把名称或其他应用的编号当作身份。 */
@Service
public class ChatMentionIdentityService {
    private final ChatBotBindingMapper rows;
    private final EnterpriseIntegrationMapper applications;
    private final ChatBotBindingService bots;
    private final ResourceJson json;

    public ChatMentionIdentityService(ChatBotBindingMapper rows, EnterpriseIntegrationMapper applications, ChatBotBindingService bots, ResourceJson json) {
        this.rows = rows;
        this.applications = applications;
        this.bots = bots;
        this.json = json;
    }

    public record Pair(String peerInFirst, String firstInPeer) {
    }

    /** 调用者先确认消息、群、发起人、标记、正文及有效期一致，再核对两份提及位置。 */
    public Pair match(ChatBotBindingRow first, JsonNode proof, ChatBotBindingRow peer, JsonNode other) {
        var firstSlots = slots(proof.path("mentionDetails"));
        var peerSlots = slots(other.path("mentionDetails"));
        if (firstSlots == null || peerSlots == null) {
            return null;
        }
        if (firstSlots.isEmpty() && peerSlots.isEmpty()) {
            // 兼容原来实际验证过相同编号的平台事件，不按名称补造对应关系。
            var ids = new HashSet<String>();
            proof.path("mentions").forEach(value -> ids.add(value.asText()));
            return proof.path("mentions").equals(other.path("mentions"))
                && ids.containsAll(List.of(first.getPlatformBotId(), peer.getPlatformBotId()))
                ? new Pair(peer.getPlatformBotId(), first.getPlatformBotId()) : null;
        }
        if (!firstSlots.keySet().equals(peerSlots.keySet())) {
            return null;
        }
        var firstSelf = firstSlots.entrySet().stream().filter(entry -> entry.getValue().equals(first.getPlatformBotId())).toList();
        var peerSelf = peerSlots.entrySet().stream().filter(entry -> entry.getValue().equals(peer.getPlatformBotId())).toList();
        if (firstSelf.size() != 1 || peerSelf.size() != 1 || firstSelf.getFirst().getKey().equals(peerSelf.getFirst().getKey())) {
            return null;
        }
        String peerInFirst = firstSlots.get(peerSelf.getFirst().getKey());
        String firstInPeer = peerSlots.get(firstSelf.getFirst().getKey());
        if (peerInFirst.equals(first.getPlatformBotId()) || firstInPeer.equals(peer.getPlatformBotId())) {
            return null;
        }
        return new Pair(peerInFirst, firstInPeer);
    }

    public void save(ChatBotBindingRow first, ChatBotBindingRow peer, Pair pair, String messageId) {
        saveAlias(first, peer, pair.peerInFirst(), messageId);
        saveAlias(peer, first, pair.firstInPeer(), messageId);
    }

    public List<Mention> resolve(ChatBotBindingRow receiver, List<Mention> mentions) {
        var aliases = json.read(receiver.getConfigJson()).path("verifiedBotMentions");
        if (!aliases.isObject()) {
            return mentions;
        }
        var peerIds = new HashSet<String>();
        aliases.fieldNames().forEachRemaining(peerIds::add);
        var peers = peerIds.isEmpty() ? List.<ChatBotBindingRow>of() : rows.selectList(new LambdaQueryWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getEnterpriseId, receiver.getEnterpriseId()).in(ChatBotBindingRow::getId, peerIds));
        var connectionIds = peers.stream().map(ChatBotBindingRow::getConnectionId).collect(Collectors.toSet());
        connectionIds.add(receiver.getConnectionId());
        var apps = applications.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, receiver.getEnterpriseId()).in(EnterpriseIntegrationRow::getId, connectionIds))
            .stream().collect(Collectors.toMap(EnterpriseIntegrationRow::getId, Function.identity()));
        var source = apps.get(receiver.getConnectionId());
        var known = new HashMap<String, String>();
        known.put(receiver.getPlatformBotId(), receiver.getPlatformBotId());
        for (var peer : peers) {
            var target = apps.get(peer.getConnectionId());
            var alias = aliases.path(peer.getId());
            if (validAlias(receiver, peer, source, target, alias)) {
                known.put(alias.path("externalId").asText(), peer.getPlatformBotId());
            }
        }
        return mentions.stream().map(mention -> {
            String canonical = known.get(mention.externalId());
            if (canonical != null) {
                return new Mention(canonical, mention.name(), "bot", mention.placeholder());
            }
            // 未验证对象只保留本条消息中的提及位置供展示，不能匹配成某个岗位。
            String position = mention.placeholder() == null || mention.placeholder().isBlank()
                ? receiver.getId() + ":" + mention.externalId() : mention.placeholder();
            return new Mention("mention:" + position, mention.name(), "unknown", mention.placeholder());
        }).toList();
    }

    public boolean supportsGroup(List<ChatBotBindingRow> selected) {
        if (selected.stream().noneMatch(bot -> json.read(bot.getConfigJson()).has("verifiedBotMentions"))) {
            return true;
        }
        var apps = applications.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, selected.getFirst().getEnterpriseId())
            .in(EnterpriseIntegrationRow::getId, selected.stream().map(ChatBotBindingRow::getConnectionId).distinct().toList()))
            .stream().collect(Collectors.toMap(EnterpriseIntegrationRow::getId, Function.identity()));
        for (var receiver : selected) {
            var aliases = json.read(receiver.getConfigJson()).path("verifiedBotMentions");
            for (var peer : selected) {
                if (!receiver.getId().equals(peer.getId()) && !validAlias(receiver, peer, apps.get(receiver.getConnectionId()),
                    apps.get(peer.getConnectionId()), aliases.path(peer.getId()))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean validAlias(ChatBotBindingRow receiver, ChatBotBindingRow peer, EnterpriseIntegrationRow source,
                               EnterpriseIntegrationRow target, JsonNode alias) {
        return source != null && target != null && "enabled".equals(source.getStatus()) && "enabled".equals(target.getStatus())
            && "feishu".equals(source.getProviderCode()) && "feishu".equals(target.getProviderCode())
            && source.getExternalTenantId() != null && source.getExternalTenantId().equals(target.getExternalTenantId())
            && source.getExternalTenantId().equals(alias.path("tenantId").asText())
            && source.getExternalAppId().equals(alias.path("sourceAppId").asText())
            && target.getExternalAppId().equals(alias.path("targetAppId").asText())
            && source.getCredentialRevision() == alias.path("sourceCredentialRevision").asLong(-1)
            && target.getCredentialRevision() == alias.path("targetCredentialRevision").asLong(-1)
            && receiver.getPlatformBotId().equals(alias.path("sourceBotId").asText())
            && peer.getPlatformBotId().equals(alias.path("targetBotId").asText())
            && !alias.path("externalId").asText().isBlank();
    }

    private void saveAlias(ChatBotBindingRow receiver, ChatBotBindingRow peer, String externalId, String messageId) {
        var current = bots.require(receiver.getEnterpriseId(), receiver.getId());
        var source = bots.application(current);
        var target = bots.application(peer);
        var config = (ObjectNode) json.read(current.getConfigJson());
        var aliases = config.withObject("/verifiedBotMentions");
        aliases.set(peer.getId(), json.tree(Map.of("externalId", externalId, "messageId", messageId,
            "sourceAppId", source.externalAppId(), "targetAppId", target.externalAppId(), "tenantId", source.externalTenantId(),
            "sourceBotId", current.getPlatformBotId(), "targetBotId", peer.getPlatformBotId(),
            "sourceCredentialRevision", source.credentialRevision(), "targetCredentialRevision", target.credentialRevision())));
        if (rows.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, current.getEnterpriseId())
            .eq(ChatBotBindingRow::getId, current.getId()).eq(ChatBotBindingRow::getRevision, current.getRevision())
            .set(ChatBotBindingRow::getConfigJson, json.write(config))) != 1) {
            throw ApiException.versionConflict(current.getRevision());
        }
    }

    private Map<String, String> slots(JsonNode details) {
        var result = new HashMap<String, String>();
        boolean missing = false;
        for (var mention : details) {
            String placeholder = mention.path("placeholder").asText("");
            String id = mention.path("externalId").asText("");
            if (placeholder.isBlank()) {
                missing = true;
            } else if (id.isBlank() || result.putIfAbsent(placeholder, id) != null) {
                return null;
            }
        }
        return missing && !result.isEmpty() ? null : result;
    }
}
