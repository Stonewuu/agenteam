package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatResourceLease;
import com.stonewu.agenteam.model.file.request.FilePrepareRequest;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 下载与文件检查分别恢复；只有原用户已绑定且群仍启用时才取得平台资源。 */
@Service
public class ChatResourceTransactions {
    private final ChatMessageResourceMapper resources;
    private final ChatMessageMapper messages;
    private final ChatBotBindingService bots;
    private final ChatConversationService conversations;
    private final ChatIdentityService identities;
    private final ChatPayloadCipher cipher;
    private final EnterpriseMapper enterprises;
    private final FileMapper files;
    private final BackgroundJobMapper jobs;
    private final ResourceJson json;
    private final Clock clock;

    public ChatResourceTransactions(ChatMessageResourceMapper resources, ChatMessageMapper messages, ChatBotBindingService bots,
            ChatConversationService conversations, ChatIdentityService identities, ChatPayloadCipher cipher, EnterpriseMapper enterprises,
            FileMapper files, BackgroundJobMapper jobs, ResourceJson json, Clock clock) {
        this.resources = resources;
        this.messages = messages;
        this.bots = bots;
        this.conversations = conversations;
        this.identities = identities;
        this.cipher = cipher;
        this.enterprises = enterprises;
        this.files = files;
        this.jobs = jobs;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Optional<ChatResourceLease> claim(String owner) {
        var now = clock.instant();
        var rows = resources.selectPage(new Page<ChatMessageResourceRow>(1, 1, false), new LambdaQueryWrapper<ChatMessageResourceRow>()
            .in(ChatMessageResourceRow::getStatus, "pending", "downloading").isNull(ChatMessageResourceRow::getFileId)
            .and(value -> value.isNull(ChatMessageResourceRow::getNextAttemptAt).or().le(ChatMessageResourceRow::getNextAttemptAt, now))
            .and(value -> value.isNull(ChatMessageResourceRow::getLeaseUntil).or().le(ChatMessageResourceRow::getLeaseUntil, now))
            .orderByAsc(ChatMessageResourceRow::getUpdatedAt, ChatMessageResourceRow::getId)).getRecords();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var row = rows.getFirst();
        var message = message(row);
        if (message.getSenderUserId() == null || message.getOriginBindingId() == null) {
            boolean expired = !message.getReceivedAt().plusSeconds(300).isAfter(now);
            resources.update(new LambdaUpdateWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, row.getEnterpriseId())
                .eq(ChatMessageResourceRow::getId, row.getId()).eq(ChatMessageResourceRow::getLeaseVersion, row.getLeaseVersion())
                .set(expired, ChatMessageResourceRow::getStatus, "failed").set(expired, ChatMessageResourceRow::getErrorCode, "CHANNEL_BINDING_REQUIRED")
                .set(ChatMessageResourceRow::getNextAttemptAt, now.plusSeconds(5)).set(ChatMessageResourceRow::getUpdatedAt, now));
            return Optional.empty();
        }
        int changed = resources.update(new LambdaUpdateWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, row.getEnterpriseId())
            .eq(ChatMessageResourceRow::getId, row.getId()).eq(ChatMessageResourceRow::getLeaseVersion, row.getLeaseVersion())
            .and(value -> value.isNull(ChatMessageResourceRow::getLeaseUntil).or().le(ChatMessageResourceRow::getLeaseUntil, now))
            .set(ChatMessageResourceRow::getStatus, "downloading").set(ChatMessageResourceRow::getLeaseOwner, owner)
            .set(ChatMessageResourceRow::getLeaseUntil, now.plusSeconds(30)).set(ChatMessageResourceRow::getLeaseVersion, row.getLeaseVersion() + 1)
            .setIncrBy(ChatMessageResourceRow::getAttemptCount, 1));
        return changed == 1 ? Optional.of(new ChatResourceLease(row.getEnterpriseId(), row.getId(), row.getLeaseVersion() + 1, owner)) : Optional.empty();
    }

    public Prepared prepare(ChatResourceLease lease) {
        var row = owned(lease);
        if (row == null) {
            throw expired();
        }
        var message = message(row);
        if (Set.of("failed", "ignored").contains(message.getRoutingStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_RESOURCE_FORBIDDEN", "此消息已停止处理，不再下载其他附件。");
        }
        identities.actor(lease.enterpriseId(), message.getSenderUserId(), message.getOriginBindingId());
        var bot = bots.require(lease.enterpriseId(), row.getSourceBotId());
        var app = bots.application(bot);
        var group = conversations.require(lease.enterpriseId(), message.getConversationId());
        var member = conversations.member(lease.enterpriseId(), group.getId(), bot.getId());
        if (!group.getStatus().equals("enabled") || !bot.getStatus().equals("enabled") || !Boolean.TRUE.equals(bot.getChatEnabled())
            || member == null || !member.getMembershipStatus().equals("present") || !Boolean.TRUE.equals(member.getReceiveEnabled())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_RESOURCE_FORBIDDEN", "原会话已不允许读取此附件。");
        }
        if (app.providerCode().equals("wecom_aibot") && !message.getReceivedAt().plusSeconds(300).isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_RESOURCE_EXPIRED", "平台附件下载地址已过期，请重新发送附件。");
        }
        var material = cipher.decrypt(lease.enterpriseId(), "message-resource", row.getId(), row.getFetchMaterialEncrypted());
        String name = material.path("name").asText();
        String mediaType = app.providerCode().equals("wecom_aibot") && material.path("type").asText().equals("file")
            ? null : ChatFilePolicy.mediaType(name, material.path("type").asText());
        return new Prepared(app, message.getPlatformMessageId(), message.getSenderUserId(), name, mediaType, material.path("download"));
    }

    @Transactional
    public void fetched(ChatResourceLease lease, Prepared prepared, ChatPlatformAdapter.Downloaded downloaded) {
        String media = ChatFilePolicy.mediaType(downloaded.name(), "file");
        var resolved = new Prepared(prepared.application(), prepared.platformMessageId(), prepared.userId(), downloaded.name(), media, prepared.material());
        downloaded(lease, resolved, downloaded.content());
    }

    @Transactional
    public void downloaded(ChatResourceLease lease, Prepared prepared, FileContentStorage.Stored content) {
        enterprises.lockEnterprise(lease.enterpriseId()).orElseThrow();
        var row = owned(lease);
        if (row == null) {
            return;
        }
        var current = prepare(lease);
        if (!current.userId().equals(prepared.userId()) || current.application().credentialRevision() != prepared.application().credentialRevision()) {
            throw expired();
        }
        if (content.size() < 1 || content.size() > 20L * 1024 * 1024) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_RESOURCE_TOO_LARGE", "附件为空或超过二十 MiB，请重新发送。");
        }
        long total = resources.selectList(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatMessageResourceRow::getMessageId, row.getMessageId()).ne(ChatMessageResourceRow::getId, row.getId())).stream()
            .mapToLong(value -> value.getSizeBytes() == null ? 0 : value.getSizeBytes()).sum();
        if (total + content.size() > 50L * 1024 * 1024) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_RESOURCE_TOO_LARGE", "同一条消息的附件合计超过五十 MiB，请拆分发送。");
        }
        String fileId = UUID.randomUUID().toString();
        var input = new FilePrepareRequest("collaboration_artifact", null, prepared.name(), content.size(), prepared.mediaType(), content.sha256());
        files.prepare(fileId, lease.enterpriseId(), prepared.userId(), input, prepared.mediaType(), clock.instant());
        var file = files.find(lease.enterpriseId(), fileId, true).orElseThrow();
        String uploadLease = UUID.randomUUID().toString();
        if (!files.claimUpload(file, uploadLease, clock.instant()) || !files.uploaded(file, uploadLease, content.key(), content.size(), content.sha256(), clock.instant())) {
            throw new IllegalStateException("平台附件登记未能完成");
        }
        files.scanning(files.find(lease.enterpriseId(), fileId, false).orElseThrow(), clock.instant());
        String job = UUID.randomUUID().toString();
        jobs.enqueue(job, lease.enterpriseId(), prepared.userId(), "file_scan", "file_scan:" + fileId, json.write(json.tree(Map.of("fileId", fileId))), clock.instant());
        jobs.setAttemptLimit(job, 1440);
        if (resources.update(guard(lease).set(ChatMessageResourceRow::getFileId, fileId).set(ChatMessageResourceRow::getSizeBytes, content.size())
            .set(ChatMessageResourceRow::getSha256, content.sha256()).set(ChatMessageResourceRow::getFetchMaterialEncrypted, null)
            .set(ChatMessageResourceRow::getLeaseOwner, null)
            .set(ChatMessageResourceRow::getLeaseUntil, null).set(ChatMessageResourceRow::getUpdatedAt, clock.instant())) != 1) {
            throw expired();
        }
    }

    public List<ChatMessageResourceRow> awaitingInspection() {
        return resources.selectPage(new Page<ChatMessageResourceRow>(1, 50, false), new LambdaQueryWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getStatus, "downloading").isNotNull(ChatMessageResourceRow::getFileId)
            .orderByAsc(ChatMessageResourceRow::getUpdatedAt, ChatMessageResourceRow::getId)).getRecords();
    }

    @Transactional
    public void inspected(String enterprise, String resource) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var row = resources.selectOne(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, enterprise)
            .eq(ChatMessageResourceRow::getId, resource).eq(ChatMessageResourceRow::getStatus, "downloading").isNotNull(ChatMessageResourceRow::getFileId));
        if (row == null) {
            return;
        }
        var file = files.find(enterprise, row.getFileId(), false).orElseThrow();
        if (Set.of("pending", "uploaded", "scanning").contains(file.status())) {
            resources.update(new LambdaUpdateWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getId, resource)
                .eq(ChatMessageResourceRow::getEnterpriseId, enterprise).set(ChatMessageResourceRow::getUpdatedAt, clock.instant()));
            return;
        }
        resources.update(new LambdaUpdateWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, enterprise)
            .eq(ChatMessageResourceRow::getId, resource).eq(ChatMessageResourceRow::getStatus, "downloading")
            .set(ChatMessageResourceRow::getStatus, file.status().equals("ready") ? "ready" : "failed")
            .set(ChatMessageResourceRow::getErrorCode, file.errorCode()).set(ChatMessageResourceRow::getUpdatedAt, clock.instant()));
    }

    @Transactional
    public void failed(ChatResourceLease lease, String code, boolean retry) {
        var row = owned(lease);
        if (row == null) {
            return;
        }
        boolean again = retry && row.getAttemptCount() < 5;
        resources.update(guard(lease).set(ChatMessageResourceRow::getStatus, again ? "pending" : code.equals("CHAT_RESOURCE_UNSUPPORTED") ? "unsupported" : "failed")
            .set(!again, ChatMessageResourceRow::getFetchMaterialEncrypted, null)
            .set(ChatMessageResourceRow::getErrorCode, code).set(ChatMessageResourceRow::getNextAttemptAt, again ? clock.instant().plusSeconds(5L * row.getAttemptCount()) : null)
            .set(ChatMessageResourceRow::getLeaseOwner, null).set(ChatMessageResourceRow::getLeaseUntil, null).set(ChatMessageResourceRow::getUpdatedAt, clock.instant()));
    }

    private ChatMessageRow message(ChatMessageResourceRow row) {
        return messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, row.getEnterpriseId()).eq(ChatMessageRow::getId, row.getMessageId()));
    }

    private ChatMessageResourceRow owned(ChatResourceLease lease) {
        return resources.selectOne(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatMessageResourceRow::getId, lease.resourceId()).eq(ChatMessageResourceRow::getLeaseOwner, lease.owner())
            .eq(ChatMessageResourceRow::getLeaseVersion, lease.version()).gt(ChatMessageResourceRow::getLeaseUntil, clock.instant()));
    }

    private LambdaUpdateWrapper<ChatMessageResourceRow> guard(ChatResourceLease lease) {
        return new LambdaUpdateWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatMessageResourceRow::getId, lease.resourceId()).eq(ChatMessageResourceRow::getLeaseOwner, lease.owner())
            .eq(ChatMessageResourceRow::getLeaseVersion, lease.version()).gt(ChatMessageResourceRow::getLeaseUntil, clock.instant());
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.CONFLICT, "CHAT_RESOURCE_LEASE_EXPIRED", "附件处理已由其他进程接管。");
    }

    public record Prepared(IntegrationApplication application, String platformMessageId, String userId, String name, String mediaType, JsonNode material) {
    }
}
