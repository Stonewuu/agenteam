package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/** 保留消息去重编号，定期清除不再需要的原始载荷和未采用的资料。 */
@Service
public class ChatRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatRetentionService.class);
    private final ChatInboundReceiptMapper receipts;
    private final ChatMessageMapper messages;
    private final ChatMessageResourceMapper resources;
    private final CollaborationArtifactMapper artifacts;
    private final ChatPayloadCipher cipher;
    private final ChatConfiguration configuration;
    private final Clock clock;
    private final ObjectProvider<ChatRetentionService> self;
    private final EnterpriseMapper enterprises;
    private final ChatCapabilityService capabilities;

    public ChatRetentionService(ChatInboundReceiptMapper receipts, ChatMessageMapper messages, ChatMessageResourceMapper resources,
            CollaborationArtifactMapper artifacts, ChatPayloadCipher cipher, ChatConfiguration configuration, Clock clock,
            ObjectProvider<ChatRetentionService> self, EnterpriseMapper enterprises, ChatCapabilityService capabilities) {
        this.receipts = receipts;
        this.messages = messages;
        this.resources = resources;
        this.artifacts = artifacts;
        this.cipher = cipher;
        this.configuration = configuration;
        this.clock = clock;
        this.self = self;
        this.enterprises = enterprises;
        this.capabilities = capabilities;
    }

    @Scheduled(fixedDelayString = "${chat.retention-poll-interval-ms:60000}")
    public void poll() {
        if (!configuration.isWorkerEnabled()) {
            return;
        }
        try {
            capabilities.expireProbes();
            self.getObject().clean();
        } catch (RuntimeException failure) {
            LOG.warn("聊天资料清理本轮未完成", ChatSocket.safe("CHAT_RETENTION_FAILED", "稍后继续清理过期资料。", failure));
        }
    }

    @Transactional
    public void clean() {
        var now = clock.instant();
        var old = receipts.selectPage(new Page<ChatInboundReceiptRow>(1, 100, false), new LambdaQueryWrapper<ChatInboundReceiptRow>()
            .isNull(ChatInboundReceiptRow::getPayloadPurgedAt).in(ChatInboundReceiptRow::getStatus, "normalized", "ignored", "failed")
            .lt(ChatInboundReceiptRow::getReceivedAt, now.minusSeconds(86400)).orderByAsc(ChatInboundReceiptRow::getReceivedAt)).getRecords();
        for (var row : old) {
            receipts.update(new LambdaUpdateWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getEnterpriseId, row.getEnterpriseId())
                .eq(ChatInboundReceiptRow::getId, row.getId()).isNull(ChatInboundReceiptRow::getPayloadPurgedAt)
                .set(ChatInboundReceiptRow::getPayloadEncrypted, cipher.encrypt(row.getEnterpriseId(), "receipt", row.getId(), Map.of("reason", "原始事件已按保留期限清理")))
                .set(ChatInboundReceiptRow::getPayloadPurgedAt, now));
        }
        var unused = messages.selectPage(new Page<ChatMessageRow>(1, 100, false), new LambdaQueryWrapper<ChatMessageRow>()
            .isNull(ChatMessageRow::getContentPurgedAt).isNull(ChatMessageRow::getTaskId)
            .in(ChatMessageRow::getRoutingStatus, "ignored", "failed", "awaiting_binding", "awaiting_clarification")
            .lt(ChatMessageRow::getReceivedAt, now.minusSeconds(86400)).orderByAsc(ChatMessageRow::getReceivedAt)).getRecords();
        for (var row : unused.stream().sorted(Comparator.comparing(ChatMessageRow::getEnterpriseId)).toList()) {
            enterprises.lockEnterprise(row.getEnterpriseId()).orElseThrow();
            int changed = messages.update(new LambdaUpdateWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, row.getEnterpriseId())
                .eq(ChatMessageRow::getId, row.getId()).isNull(ChatMessageRow::getTaskId).eq(ChatMessageRow::getRoutingStatus, row.getRoutingStatus())
                .set(ChatMessageRow::getContentEncrypted, cipher.encrypt(row.getEnterpriseId(), "message", row.getId(), Map.of("reason", "未采用的消息正文已清理")))
                .set(ChatMessageRow::getSenderIdentitiesEncrypted, cipher.encrypt(row.getEnterpriseId(), "message-senders", row.getId(), Map.of()))
                .set(ChatMessageRow::getMentionsJson, "[]").set(ChatMessageRow::getRoutingStatus, "ignored").set(ChatMessageRow::getContentPurgedAt, now));
            if (changed == 1) {
                releaseFiles(row);
            }
        }
    }

    private void releaseFiles(ChatMessageRow message) {
        var unused = resources.selectList(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, message.getEnterpriseId())
            .eq(ChatMessageResourceRow::getMessageId, message.getId()));
        var ids = unused.stream().map(ChatMessageResourceRow::getFileId).filter(value -> value != null).toList();
        Set<String> retained = ids.isEmpty() ? Set.of() : artifacts.selectList(new LambdaQueryWrapper<CollaborationArtifactRow>()
            .eq(CollaborationArtifactRow::getEnterpriseId, message.getEnterpriseId()).in(CollaborationArtifactRow::getFileId, ids))
            .stream().map(CollaborationArtifactRow::getFileId).collect(Collectors.toSet());
        for (var resource : unused) {
            resources.update(new LambdaUpdateWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, message.getEnterpriseId())
                .eq(ChatMessageResourceRow::getId, resource.getId()).set(ChatMessageResourceRow::getFetchMaterialEncrypted, null)
                .set(ChatMessageResourceRow::getStatus, "failed").set(ChatMessageResourceRow::getErrorCode, "CHAT_RESOURCE_EXPIRED")
                .set(resource.getFileId() != null && !retained.contains(resource.getFileId()), ChatMessageResourceRow::getFileId, null)
                .set(ChatMessageResourceRow::getLeaseOwner, null).set(ChatMessageResourceRow::getLeaseUntil, null));
        }
    }
}
