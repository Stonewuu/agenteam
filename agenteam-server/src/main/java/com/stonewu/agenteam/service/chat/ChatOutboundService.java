package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** 业务动作和固定发送记录同事务保存；发送失败不会重新运行智能体。 */
@Service
public class ChatOutboundService {
    private final ChatOutboundMessageMapper messages;
    private final ChatMessageMapper incoming;
    private final CollaborationTaskMapper tasks;
    private final EnterpriseMapper enterprises;
    private final ChatConversationService conversations;
    private final ChatBotBindingMapper bots;
    private final EnterpriseIntegrationMapper integrations;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ChatPlatformRegistry platforms;

    public ChatOutboundService(ChatOutboundMessageMapper messages, CollaborationTaskMapper tasks, EnterpriseMapper enterprises,
            ChatConversationService conversations, ChatBotBindingMapper bots, EnterpriseIntegrationMapper integrations, ChatPayloadCipher cipher,
            ResourceJson json, ObjectMapper mapper, Clock clock, ChatMessageMapper incoming, ChatPlatformRegistry platforms) {
        this.messages = messages;
        this.incoming = incoming;
        this.tasks = tasks;
        this.enterprises = enterprises;
        this.conversations = conversations;
        this.bots = bots;
        this.integrations = integrations;
        this.cipher = cipher;
        this.json = json;
        this.mapper = mapper;
        this.clock = clock;
        this.platforms = platforms;
    }

    @Transactional
    public void progress(String enterprise, String conversation, String botId, String taskId,
            String actionId, String key, String kind, String text, String replyTo) {
        if ("accepted".equals(kind) || !"direct".equals(conversations.require(enterprise, conversation).getConversationKind())) {
            text(enterprise, conversation, botId, taskId, actionId, key, kind, text, replyTo);
        }
    }

    @Transactional
    public ChatOutboundMessageRow file(String enterprise, String conversation, String botId, String taskId, String actionId,
            String key, CollaborationArtifactRow artifact) {
        var row = text(enterprise, conversation, botId, taskId, actionId, key, artifact == null ? "file_probe" : "completed_file", "", null);
        var payload = (ObjectNode) cipher.decrypt(enterprise, "outbound", row.getId(), row.getPayloadEncrypted());
        if (payload.path("messageType").asText().equals("file")) {
            if (artifact == null ? !payload.path("syntheticFile").asBoolean() : !artifact.getId().equals(payload.path("artifactId").asText())) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_REQUEST_CONFLICT", "同一发送请求不能更换成果。");
            }
            return row;
        }
        payload.put("messageType", "file");
        if (artifact == null) {
            payload.put("syntheticFile", true);
        } else {
            payload.put("artifactId", artifact.getId()).put("artifactVersion", artifact.getArtifactVersion()).put("fileId", artifact.getFileId());
        }
        if (messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
            .eq(ChatOutboundMessageRow::getId, row.getId()).isNull(ChatOutboundMessageRow::getFirstSubmittedAt)
            .set(ChatOutboundMessageRow::getPayloadEncrypted, cipher.encrypt(enterprise, "outbound", row.getId(), payload))
            .set(ChatOutboundMessageRow::getPayloadHash, json.hash(payload))) != 1) {
            throw new IllegalStateException("成果发送记录保存失败");
        }
        return row;
    }

    @Transactional
    public ChatOutboundMessageRow settings(String enterprise, String conversation, String botId, String taskId,
            String key, String text, String url, boolean full) {
        var row = text(enterprise, conversation, botId, taskId, null, key, "reply_settings", text, null);
        if (row.getFirstSubmittedAt() != null) {
            return row;
        }
        var payload = (ObjectNode) cipher.decrypt(enterprise, "outbound", row.getId(), row.getPayloadEncrypted());
        payload.put("replySettingsUrl", url).put("replyFull", full);
        if (messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
                .eq(ChatOutboundMessageRow::getId, row.getId()).isNull(ChatOutboundMessageRow::getFirstSubmittedAt)
                .set(ChatOutboundMessageRow::getPayloadEncrypted, cipher.encrypt(enterprise, "outbound", row.getId(), payload))
                .set(ChatOutboundMessageRow::getPayloadHash, json.hash(payload))) != 1) {
            throw new IllegalStateException("回复方式入口保存失败");
        }
        return row;
    }

    @Transactional
    public ChatOutboundMessageRow cardProbe(String enterprise, String conversation, String botId, String operationId) {
        var row = text(enterprise, conversation, botId, null, null, "card-probe:" + operationId, "card_probe",
            "请点击下方按钮，确认可以在飞书里修改回复方式。", null);
        if (row.getFirstSubmittedAt() == null) {
            var payload = (ObjectNode) cipher.decrypt(enterprise, "outbound", row.getId(), row.getPayloadEncrypted());
            payload.put("replyCardProbe", true);
            if (messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
                    .eq(ChatOutboundMessageRow::getId, row.getId()).isNull(ChatOutboundMessageRow::getFirstSubmittedAt)
                    .set(ChatOutboundMessageRow::getPayloadEncrypted, cipher.encrypt(enterprise, "outbound", row.getId(), payload))
                    .set(ChatOutboundMessageRow::getPayloadHash, json.hash(payload))) != 1) {
                throw new IllegalStateException("卡片检查消息保存失败");
            }
        }
        return row;
    }

    @Transactional
    public void updateSettingsCard(ChatOutboundMessageRow original, String key, boolean editing) {
        var source = cipher.decrypt(original.getEnterpriseId(), "outbound", original.getId(), original.getPayloadEncrypted());
        var row = text(original.getEnterpriseId(), original.getConversationId(), original.getBotBindingId(), original.getTaskId(),
            null, key, "reply_settings_update", source.path("content").path("text").asText(), null);
        if (row.getFirstSubmittedAt() != null) {
            return;
        }
        var payload = (ObjectNode) cipher.decrypt(row.getEnterpriseId(), "outbound", row.getId(), row.getPayloadEncrypted());
        payload.put("messageType", "interactive_update").put("updateMessageId", original.getPlatformMessageId())
            .put("replySettingsEditing", editing).put("replySettingsOriginalId", original.getId());
        var previous = messages.selectPage(new Page<ChatOutboundMessageRow>(1, 1, false), new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, row.getEnterpriseId()).eq(ChatOutboundMessageRow::getTaskId, row.getTaskId())
            .eq(ChatOutboundMessageRow::getKind, "reply_settings_update").lt(ChatOutboundMessageRow::getTaskSequence, row.getTaskSequence())
            .orderByDesc(ChatOutboundMessageRow::getTaskSequence)).getRecords();
        if (messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, row.getEnterpriseId())
                .eq(ChatOutboundMessageRow::getId, row.getId()).isNull(ChatOutboundMessageRow::getFirstSubmittedAt)
                .set(!previous.isEmpty(), ChatOutboundMessageRow::getDependsOnOutboundId, previous.isEmpty() ? null : previous.getFirst().getId())
                .set(ChatOutboundMessageRow::getPayloadEncrypted, cipher.encrypt(row.getEnterpriseId(), "outbound", row.getId(), payload))
                .set(ChatOutboundMessageRow::getPayloadHash, json.hash(payload))) != 1) {
            throw new IllegalStateException("卡片更新记录保存失败");
        }
    }

    @Transactional
    public ChatOutboundMessageRow text(String enterprise, String conversation, String botId, String taskId,
            String actionId, String key, String kind, String text, String replyTo) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        ChatInputValidation.size(text, 0);
        var group = conversations.require(enterprise, conversation);
        var parts = platforms.adapter(group.getPlatformCode()).splitText(text);
        String sourceHash = json.hash(json.tree(text));
        ChatOutboundMessageRow first = null;
        ChatOutboundMessageRow previous = null;
        for (int index = 0; index < parts.size(); index++) {
            var row = textPart(enterprise, conversation, botId, taskId, actionId, index == 0 ? key : key + ":part:" + index,
                kind, parts.get(index), replyTo, sourceHash, index);
            if (first == null) {
                first = row;
            }
            if (previous != null && row.getDependsOnOutboundId() == null && row.getFirstSubmittedAt() == null) {
                messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
                    .eq(ChatOutboundMessageRow::getId, row.getId()).isNull(ChatOutboundMessageRow::getDependsOnOutboundId)
                    .set(ChatOutboundMessageRow::getDependsOnOutboundId, previous.getId()));
            }
            previous = row;
        }
        return first;
    }

    private ChatOutboundMessageRow textPart(String enterprise, String conversation, String botId, String taskId,
            String actionId, String key, String kind, String text, String replyTo, String sourceHash, int partNo) {
        String dedupe = json.hash(json.tree(List.of(conversation, botId, key)));
        var existing = messages.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getDedupeKey, dedupe));
        if (existing != null) {
            var payload = cipher.decrypt(enterprise, "outbound", existing.getId(), existing.getPayloadEncrypted());
            if (!payload.path("content").path("text").asText().equals(text) || !existing.getKind().equals(kind)
                || !sourceHash.equals(payload.path("sourceTextHash").asText())) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_REQUEST_CONFLICT", "同一发送请求不能更换内容。");
            }
            return existing;
        }
        var group = conversations.require(enterprise, conversation);
        var member = conversations.member(enterprise, conversation, botId);
        var bot = bots.selectOne(new LambdaQueryWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, enterprise).eq(ChatBotBindingRow::getId, botId));
        if (bot == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_BOT_UNAVAILABLE", "机器人绑定不存在。");
        }
        var app = integrations.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)
            .eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()));
        boolean allowed = group.getStatus().equals("enabled") && member != null && member.getMembershipStatus().equals("present")
            && Boolean.TRUE.equals(member.getSendEnabled()) && bot.getStatus().equals("enabled") && app.getStatus().equals("enabled");
        var row = ChatRecords.initialize(new ChatOutboundMessageRow(), enterprise, clock.instant());
        row.setConversationId(conversation);
        row.setBotBindingId(botId);
        row.setTaskId(taskId);
        row.setActionId(actionId);
        row.setKind(kind);
        row.setDedupeKey(dedupe);
        row.setRequestId(UUID.randomUUID().toString());
        row.setStatus(allowed ? "pending" : "cancelled");
        row.setErrorCode(allowed ? null : "CHAT_SEND_FORBIDDEN");
        row.setInputRevision(0L);
        row.setTaskSequence(0L);
        row.setPartNo(partNo);
        row.setAttemptCount(0);
        row.setLeaseVersion(0L);
        CollaborationTaskRow task = null;
        if (taskId != null) {
            task = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)
                .eq(CollaborationTaskRow::getId, taskId).eq(CollaborationTaskRow::getConversationId, conversation));
            if (task == null) {
                throw ApiException.invalidField("taskId", "任务不属于当前会话。");
            }
        }
        String replyTarget = replyTarget(group, task, replyTo);
        var payload = mapper.createObjectNode().put("receiveId", member == null ? "" : member.getExternalChatId())
            .put("messageType", "text").put("requestId", row.getRequestId()).put("replyToMessageId", replyTarget)
            .put("replyInThread", replyTarget != null).put("bindingRevision", bot.getRevision())
            .put("credentialRevision", app.getCredentialRevision()).put("sourceTextHash", sourceHash);
        payload.putObject("content").put("text", text);
        if (task != null) {
            row.setInputRevision(task.getInputRevision());
            row.setTaskSequence(task.getNextSequence());
            var predecessors = messages.selectPage(new Page<ChatOutboundMessageRow>(1, 1, false), new LambdaQueryWrapper<ChatOutboundMessageRow>()
                .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getTaskId, taskId)
                .ne(ChatOutboundMessageRow::getKind, "reply_settings_update")
                .orderByDesc(ChatOutboundMessageRow::getTaskSequence, ChatOutboundMessageRow::getPartNo)).getRecords();
            if (!predecessors.isEmpty() && !"reply_settings_update".equals(kind)) {
                row.setDependsOnOutboundId(predecessors.getLast().getId());
            }
            if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)
                .eq(CollaborationTaskRow::getId, taskId).eq(CollaborationTaskRow::getNextSequence, task.getNextSequence())
                .setIncrBy(CollaborationTaskRow::getNextSequence, 1)) != 1) {
                throw new IllegalStateException("协作任务发送顺序已发生变化");
            }
        }
        row.setPayloadEncrypted(cipher.encrypt(enterprise, "outbound", row.getId(), payload));
        row.setPayloadHash(json.hash(payload));
        messages.insert(row);
        return row;
    }

    private String replyTarget(ChatConversationRow conversation, CollaborationTaskRow task, String requested) {
        if (!"feishu".equals(conversation.getPlatformCode()) || "direct".equals(conversation.getConversationKind())) {
            return null;
        }
        if (task == null) {
            return requested;
        }
        var origin = incoming.selectOne(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, task.getEnterpriseId()).eq(ChatMessageRow::getConversationId, conversation.getId())
            .eq(ChatMessageRow::getId, task.getOriginMessageId()));
        if (origin == null || origin.getPlatformMessageId() == null || origin.getPlatformMessageId().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_REPLY_UNAVAILABLE", "原消息已不可用，请重新发起任务。");
        }
        return origin.getPlatformMessageId();
    }
}
