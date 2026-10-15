package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatReceiptLease;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Kind;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Mention;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Map;

/** 接收消息的规范化与去重在事务内提交，后续模型任务由独立路由工作处理。 */
@Service
public class ChatNormalizationTransactions {
    private final ChatInboundReceiptMapper receipts;
    private final ChatMessageMapper messages;
    private final ChatMessageResourceMapper resources;
    private final ChatBotBindingMapper botRows;
    private final ChatBotBindingService bots;
    private final ChatConversationService conversations;
    private final ChatPlatformRegistry platforms;
    private final ChatMessageIdentity identities;
    private final ChatMentionIdentityService mentionIdentities;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final ObjectMapper mapper;
    private final EnterpriseMapper enterprises;
    private final Clock clock;
    private final ChatGroupActivationService activation;

    public ChatNormalizationTransactions(ChatInboundReceiptMapper receipts, ChatMessageMapper messages,
                                          ChatMessageResourceMapper resources, ChatBotBindingMapper botRows,
                                          ChatBotBindingService bots, ChatConversationService conversations,
                                          ChatPlatformRegistry platforms, ChatMessageIdentity identities, ChatPayloadCipher cipher,
                                          ResourceJson json, ObjectMapper mapper, EnterpriseMapper enterprises, Clock clock,
                                          ChatMentionIdentityService mentionIdentities, ChatGroupActivationService activation) {
        this.receipts = receipts;
        this.messages = messages;
        this.resources = resources;
        this.botRows = botRows;
        this.bots = bots;
        this.conversations = conversations;
        this.platforms = platforms;
        this.identities = identities;
        this.mentionIdentities = mentionIdentities;
        this.cipher = cipher;
        this.json = json;
        this.mapper = mapper;
        this.enterprises = enterprises;
        this.clock = clock;
        this.activation = activation;
    }

    @Transactional
    public Optional<ChatReceiptLease> claim(String owner) {
        var now = clock.instant();
        var query = new LambdaQueryWrapper<ChatInboundReceiptRow>().in(ChatInboundReceiptRow::getStatus, "received", "processing", "retry_wait")
            .and(value -> value.isNull(ChatInboundReceiptRow::getNextAttemptAt).or().le(ChatInboundReceiptRow::getNextAttemptAt, now))
            .and(value -> value.isNull(ChatInboundReceiptRow::getLeaseUntil).or().le(ChatInboundReceiptRow::getLeaseUntil, now))
            .orderByAsc(ChatInboundReceiptRow::getCreatedAt, ChatInboundReceiptRow::getId);
        var candidates = receipts.selectPage(new Page<ChatInboundReceiptRow>(1, 1, false), query).getRecords();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var row = candidates.getFirst();
        int changed = receipts.update(new LambdaUpdateWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getEnterpriseId, row.getEnterpriseId())
            .eq(ChatInboundReceiptRow::getId, row.getId()).eq(ChatInboundReceiptRow::getLeaseVersion, row.getLeaseVersion())
            .and(value -> value.isNull(ChatInboundReceiptRow::getLeaseUntil).or().le(ChatInboundReceiptRow::getLeaseUntil, now))
            .set(ChatInboundReceiptRow::getStatus, "processing").set(ChatInboundReceiptRow::getLeaseOwner, owner)
            .set(ChatInboundReceiptRow::getLeaseUntil, now.plusSeconds(30)).set(ChatInboundReceiptRow::getLeaseVersion, row.getLeaseVersion() + 1)
            .set(ChatInboundReceiptRow::getAttemptCount, row.getAttemptCount() + 1));
        return changed == 1 ? Optional.of(new ChatReceiptLease(row.getEnterpriseId(), row.getId(), row.getLeaseVersion() + 1, owner)) : Optional.empty();
    }

    @Transactional
    public void normalize(ChatReceiptLease lease) {
        enterprises.lockEnterprise(lease.enterpriseId()).orElseThrow();
        var receipt = owned(lease);
        if (receipt == null) {
            return;
        }
        var bot = bots.require(lease.enterpriseId(), receipt.getBotBindingId());
        if (!bot.getStatus().equals("enabled")) {
            finish(lease, "ignored", null, "CHAT_BOT_DISABLED");
            return;
        }
        var app = bots.application(bot);
        var envelope = cipher.decrypt(lease.enterpriseId(), "receipt", receipt.getId(), receipt.getPayloadEncrypted());
        var event = platforms.normalizer(app.providerCode()).normalize(app, bot.getPlatformBotId(), envelope, receipt.getReceivedAt());
        if (event.externalChatId() == null) {
            finish(lease, "ignored", null, null);
            return;
        }
        if (event.kind() != Kind.MESSAGE && event.kind() != Kind.BOT_JOINED && event.kind() != Kind.BOT_LEFT && event.kind() != Kind.CHAT_UPDATED) {
            finish(lease, "ignored", null, null);
            return;
        }
        var conversation = conversations.observe(bot, app, event.externalChatId(), null, event.chatKind(),
            event.kind() == Kind.BOT_JOINED ? "platform_event" : "verified_message", event.platformCreatedAt());
        conversation = activation.activate(bot, conversation, event);
        if (event.kind() == Kind.BOT_LEFT || event.kind() == Kind.CHAT_UPDATED && event.metadata().path("disbanded").asBoolean()) {
            conversations.left(lease.enterpriseId(), conversation.getId(), bot.getId(), event.kind() == Kind.CHAT_UPDATED, event.platformCreatedAt());
        }
        if (event.kind() != Kind.MESSAGE || !event.humanMessage() || !Boolean.TRUE.equals(bot.getChatEnabled()) || !conversation.getStatus().equals("enabled")) {
            finish(lease, "ignored", null, null);
            return;
        }
        var membership = conversations.member(lease.enterpriseId(), conversation.getId(), bot.getId());
        if (membership == null || !Boolean.TRUE.equals(membership.getReceiveEnabled())) {
            if (membership != null && "present".equals(membership.getMembershipStatus()) && Boolean.TRUE.equals(membership.getSendEnabled())) {
                activation.guideReceiver(bot, conversation, event);
            }
            finish(lease, "ignored", null, null);
            return;
        }
        boolean shared = conversation.getIngressMode().equals("multi_bot");
        if (shared && !bots.capability(bot, "crossBotIdentity")
            || !shared && !bot.getId().equals(conversation.getIngressBotId())) {
            finish(lease, "ignored", null, shared ? "CHAT_MULTI_BOT_CHECK_REQUIRED" : "CHAT_NOT_INGRESS");
            return;
        }
        // 自动接入的群在第二个机器人加入前就沿用已验证的企业消息编号，避免切换接收方式时重复建任务。
        boolean automatic = activation.automatic(conversation);
        boolean sharedIdentity = shared || automatic && bots.capability(bot, "crossBotIdentity");
        String key = identities.canonical(app, bot.getId(), event, sharedIdentity);
        var mentions = mentionIdentities.resolve(bot, event.mentions());
        var message = messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatMessageRow::getConversationId, conversation.getId()).and(query -> {
                query.eq(ChatMessageRow::getCanonicalKey, key);
                if (sharedIdentity && automatic) {
                    query.or().eq(ChatMessageRow::getPlatformMessageId, event.messageId());
                }
            }));
        if (message != null) {
            boolean conflict = mergeIdentity(message, bot, event, mentions);
            finish(lease, "normalized", message.getId(), conflict ? "CHAT_MESSAGE_CONTENT_CONFLICT" : null);
            return;
        }
        message = ChatRecords.initialize(new ChatMessageRow(), lease.enterpriseId(), clock.instant());
        message.setConversationId(conversation.getId());
        message.setCanonicalKey(key);
        message.setPlatformMessageId(event.messageId());
        message.setThreadId(event.threadId());
        message.setReplyToPlatformId(event.replyToMessageId());
        message.setSenderKind(event.senderKind());
        var sender = mapper.createObjectNode();
        sender.putObject(bot.getId()).put("connectionId", app.id()).put("subjectType", event.subjectType()).put("subjectId", event.senderId())
            .put("unionId", event.metadata().path("senderUnionId").asText(null));
        message.setSenderIdentitiesEncrypted(cipher.encrypt(lease.enterpriseId(), "message-senders", message.getId(), sender));
        message.setMessageType(event.messageType());
        var content = mapper.createObjectNode().put("text", ChatCommandText.display(event.text(), json.tree(mentions)))
            .put("supported", event.metadata().path("supported").asBoolean());
        content.put("receiverBotId", bot.getId());
        content.put("resourceCount", event.resources().size());
        message.setContentEncrypted(cipher.encrypt(lease.enterpriseId(), "message", message.getId(), content));
        message.setMentionsJson(json.write(json.tree(mentions)));
        message.setPlatformCreatedAt(event.platformCreatedAt());
        message.setReceivedAt(receipt.getReceivedAt());
        message.setRoutingStatus("pending");
        message.setContentHash(contentHash(event, mentions));
        message.setContentVersion(1L);
        messages.insert(message);
        int ordinal = 0;
        for (var resource : event.resources()) {
            var file = ChatRecords.initialize(new ChatMessageResourceRow(), lease.enterpriseId(), clock.instant());
            file.setMessageId(message.getId());
            file.setResourceOrdinal(ordinal++);
            file.setSourceBotId(bot.getId());
            file.setPlatformResourceKey(resource.key());
            file.setFetchMaterialEncrypted(cipher.encrypt(lease.enterpriseId(), "message-resource", file.getId(),
                Map.of("name", resource.name(), "type", resource.type(), "download", resource.downloadMaterial())));
            file.setStatus("pending");
            file.setSizeBytes(0L);
            file.setAttemptCount(0);
            file.setLeaseVersion(0L);
            resources.insert(file);
        }
        botRows.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatBotBindingRow::getId, bot.getId()).set(ChatBotBindingRow::getLastMessageAt, clock.instant()));
        finish(lease, "normalized", message.getId(), null);
    }

    @Transactional
    public void failed(ChatReceiptLease lease, String code) {
        var row = owned(lease);
        if (row == null) {
            return;
        }
        boolean terminal = row.getAttemptCount() >= 5;
        receipts.update(guard(lease).set(ChatInboundReceiptRow::getStatus, terminal ? "failed" : "retry_wait")
            .set(ChatInboundReceiptRow::getNextAttemptAt, clock.instant().plusSeconds(Math.min(60, 5L * row.getAttemptCount())))
            .set(ChatInboundReceiptRow::getErrorCode, code).set(ChatInboundReceiptRow::getLeaseUntil, null)
            .set(ChatInboundReceiptRow::getLeaseOwner, null).set(ChatInboundReceiptRow::getUpdatedAt, clock.instant()));
    }

    private boolean mergeIdentity(ChatMessageRow message, ChatBotBindingRow bot, NormalizedChatEvent event, List<Mention> mentions) {
        var identities = cipher.decrypt(message.getEnterpriseId(), "message-senders", message.getId(), message.getSenderIdentitiesEncrypted());
        ObjectNode changed = (ObjectNode) identities.deepCopy();
        var originalSender = identities.path(bot.getId());
        boolean senderConflict = !originalSender.isMissingNode() && (!originalSender.path("subjectType").asText().equals(event.subjectType())
            || !originalSender.path("subjectId").asText().equals(event.senderId())
            || originalSender.hasNonNull("unionId") && event.metadata().hasNonNull("senderUnionId")
                && !originalSender.path("unionId").asText().equals(event.metadata().path("senderUnionId").asText()));
        if (!senderConflict) {
            changed.putObject(bot.getId()).put("connectionId", bot.getConnectionId())
                .put("subjectType", event.subjectType()).put("subjectId", event.senderId())
                .put("unionId", event.metadata().path("senderUnionId").asText(originalSender.path("unionId").asText(null)));
        }
        var before = new HashSet<String>();
        json.read(message.getMentionsJson()).forEach(value -> before.add(value.path("externalId").asText()));
        var after = new HashSet<String>();
        mentions.forEach(value -> after.add(value.externalId()));
        boolean conflict = senderConflict || !before.equals(after) || !message.getContentHash().equals(contentHash(event, mentions));
        messages.update(new LambdaUpdateWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, message.getEnterpriseId())
            .eq(ChatMessageRow::getId, message.getId()).set(ChatMessageRow::getSenderIdentitiesEncrypted,
                cipher.encrypt(message.getEnterpriseId(), "message-senders", message.getId(), changed))
            .set(conflict && message.getTaskId() == null, ChatMessageRow::getRoutingStatus, "awaiting_clarification")
            .set(ChatMessageRow::getUpdatedAt, clock.instant()));
        return conflict;
    }

    private String contentHash(NormalizedChatEvent event, List<Mention> resolvedMentions) {
        var mentions = resolvedMentions.stream().map(Mention::externalId).sorted().toList();
        var files = event.resources().stream().map(resource -> List.of(resource.key() == null ? "" : resource.key(), resource.type())).toList();
        return json.hash(json.tree(List.of(event.messageType(), event.text(), mentions, files)));
    }

    private ChatInboundReceiptRow owned(ChatReceiptLease lease) {
        return receipts.selectOne(new LambdaQueryWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatInboundReceiptRow::getId, lease.receiptId()).eq(ChatInboundReceiptRow::getLeaseOwner, lease.owner())
            .eq(ChatInboundReceiptRow::getLeaseVersion, lease.version()).gt(ChatInboundReceiptRow::getLeaseUntil, clock.instant()));
    }

    private LambdaUpdateWrapper<ChatInboundReceiptRow> guard(ChatReceiptLease lease) {
        return new LambdaUpdateWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatInboundReceiptRow::getId, lease.receiptId()).eq(ChatInboundReceiptRow::getLeaseOwner, lease.owner())
            .eq(ChatInboundReceiptRow::getLeaseVersion, lease.version());
    }

    private void finish(ChatReceiptLease lease, String status, String message, String error) {
        if (receipts.update(guard(lease).set(ChatInboundReceiptRow::getStatus, status).set(ChatInboundReceiptRow::getMessageId, message)
            .set(ChatInboundReceiptRow::getPayloadEncrypted, cipher.encrypt(lease.enterpriseId(), "receipt", lease.receiptId(), Map.of("reason", "事件规范化处理已完成")))
            .set(ChatInboundReceiptRow::getPayloadPurgedAt, clock.instant())
            .set(ChatInboundReceiptRow::getErrorCode, error).set(ChatInboundReceiptRow::getLeaseOwner, null)
            .set(ChatInboundReceiptRow::getLeaseUntil, null).set(ChatInboundReceiptRow::getUpdatedAt, clock.instant())) != 1) {
            throw new IllegalStateException("接收处理已由其他进程接管，当前事务不能提交");
        }
    }
}
