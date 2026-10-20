package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.chat.ChatOutboundAttemptMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundAttemptRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatSendLease;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.Map;

/** 网络请求前后分别提交；可能已经发送的消息只在平台去重期限内重试同一请求。 */
@Service
public class ChatSendTransactions {
    private final ChatOutboundMessageMapper messages;
    private final ChatOutboundAttemptMapper attempts;
    private final CollaborationParticipantMapper participants;
    private final EnterpriseMapper enterprises;
    private final ChatBotBindingService bots;
    private final ChatConversationService conversations;
    private final CollaborationAccessService access;
    private final ChatPlatformRegistry platforms;
    private final ChatPayloadCipher cipher;
    private final Clock clock;
    private final ChatConnectionRegistry connections;
    private final ChatCapabilityService capabilities;
    private final ChatFileSendService files;
    private final ResourceJson json;
    private final ChatReplyDeliveryPolicy replyPolicy;

    public ChatSendTransactions(ChatOutboundMessageMapper messages, ChatOutboundAttemptMapper attempts,
            CollaborationParticipantMapper participants, EnterpriseMapper enterprises, ChatBotBindingService bots,
            ChatConversationService conversations, CollaborationAccessService access, ChatPlatformRegistry platforms,
            ChatPayloadCipher cipher, Clock clock, ChatConnectionRegistry connections,
            ChatCapabilityService capabilities, ChatFileSendService files, ResourceJson json, ChatReplyDeliveryPolicy replyPolicy) {
        this.messages = messages;
        this.attempts = attempts;
        this.participants = participants;
        this.enterprises = enterprises;
        this.bots = bots;
        this.conversations = conversations;
        this.access = access;
        this.platforms = platforms;
        this.cipher = cipher;
        this.clock = clock;
        this.connections = connections;
        this.capabilities = capabilities;
        this.files = files;
        this.json = json;
        this.replyPolicy = replyPolicy;
    }

    @Transactional
    public Optional<ChatSendLease> claim(String owner) {
        var now = clock.instant();
        var local = connections.localBotIds();
        var candidates = messages.selectJoinPage(new Page<ChatOutboundMessageRow>(1, 1, false), ChatOutboundMessageRow.class,
            JoinWrappers.lambda(ChatOutboundMessageRow.class).selectAll(ChatOutboundMessageRow.class)
                .innerJoin(ChatConversationRow.class, on -> on.eq(ChatConversationRow::getId, ChatOutboundMessageRow::getConversationId)
                    .eq(ChatConversationRow::getEnterpriseId, ChatOutboundMessageRow::getEnterpriseId))
                .and(value -> {
                    value.ne(ChatConversationRow::getPlatformCode, "wecom_aibot");
                    if (!local.isEmpty()) {
                        value.or().in(ChatOutboundMessageRow::getBotBindingId, local);
                    }
                }).in(ChatOutboundMessageRow::getStatus, "pending", "retry_wait", "leased", "sending")
                .and(value -> value.isNull(ChatOutboundMessageRow::getNextAttemptAt).or().le(ChatOutboundMessageRow::getNextAttemptAt, now))
                .and(value -> value.isNull(ChatOutboundMessageRow::getLeaseUntil).or().le(ChatOutboundMessageRow::getLeaseUntil, now))
                .orderByAsc(ChatOutboundMessageRow::getCreatedAt, ChatOutboundMessageRow::getId)).getRecords();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var row = candidates.getFirst();
        enterprises.lockEnterprise(row.getEnterpriseId()).orElseThrow();
        var group = conversations.require(row.getEnterpriseId(), row.getConversationId());
        var adapter = platforms.adapter(group.getPlatformCode());
        var update = new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, row.getEnterpriseId())
            .eq(ChatOutboundMessageRow::getId, row.getId()).eq(ChatOutboundMessageRow::getLeaseVersion, row.getLeaseVersion())
            .eq(ChatOutboundMessageRow::getStatus, row.getStatus())
            .and(value -> value.isNull(ChatOutboundMessageRow::getLeaseUntil).or().le(ChatOutboundMessageRow::getLeaseUntil, now));
        if (row.getStatus().equals("sending") && (row.getAttemptCount() >= 5 || !canRepeat(row, adapter.deduplicationWindow()))) {
            messages.update(update.set(ChatOutboundMessageRow::getStatus, "unknown").set(ChatOutboundMessageRow::getErrorCode, "CHAT_SEND_UNKNOWN")
                .set(ChatOutboundMessageRow::getLeaseOwner, null).set(ChatOutboundMessageRow::getLeaseUntil, null));
            return Optional.empty();
        }
        if (row.getDependsOnOutboundId() != null) {
            var previous = messages.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, row.getEnterpriseId())
                .eq(ChatOutboundMessageRow::getId, row.getDependsOnOutboundId()));
            boolean finished = previous == null || Set.of("sent", "failed", "cancelled", "unknown").contains(previous.getStatus());
            if (!finished && previous.getCreatedAt().plusSeconds(30).isBefore(now)
                && Set.of("progress", "started", "accepted", "handed_off", "assigned", "waiting_dependency", "work_completed", "input_added", "waiting_approval").contains(previous.getKind())
                && !Set.of("leased", "sending").contains(previous.getStatus())) {
                messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, row.getEnterpriseId())
                    .eq(ChatOutboundMessageRow::getId, previous.getId()).eq(ChatOutboundMessageRow::getStatus, previous.getStatus())
                    .set(ChatOutboundMessageRow::getStatus, "cancelled").set(ChatOutboundMessageRow::getErrorCode, "CHAT_PROGRESS_SUPERSEDED"));
                finished = true;
            }
            if (!finished) {
                messages.update(update.set(ChatOutboundMessageRow::getNextAttemptAt, now.plusSeconds(5)));
                return Optional.empty();
            }
        }
        int changed = messages.update(update.set(ChatOutboundMessageRow::getStatus, "leased").set(ChatOutboundMessageRow::getLeaseOwner, owner)
            .set(ChatOutboundMessageRow::getLeaseUntil, now.plusSeconds(60)).set(ChatOutboundMessageRow::getLeaseVersion, row.getLeaseVersion() + 1));
        if (changed != 1) {
            return Optional.empty();
        }
        if (row.getStatus().equals("sending")) {
            attempts.update(new LambdaUpdateWrapper<ChatOutboundAttemptRow>().eq(ChatOutboundAttemptRow::getEnterpriseId, row.getEnterpriseId())
                .eq(ChatOutboundAttemptRow::getOutboundId, row.getId()).isNull(ChatOutboundAttemptRow::getFinishedAt)
                .set(ChatOutboundAttemptRow::getOutcome, "UNKNOWN").set(ChatOutboundAttemptRow::getFinishedAt, now)
                .set(ChatOutboundAttemptRow::getErrorSummary, "发送进程失去连接，平台结果尚未确认"));
        }
        return Optional.of(new ChatSendLease(row.getEnterpriseId(), row.getId(), row.getLeaseVersion() + 1, owner));
    }

    public Prepared prepare(ChatSendLease lease) {
        var row = requireOwned(lease);
        var app = authorize(row);
        var payload = cipher.decrypt(row.getEnterpriseId(), "outbound", row.getId(), row.getPayloadEncrypted());
        if (row.getRenderedPayloadEncrypted() != null && (row.getFirstSubmittedAt() != null || "file".equals(payload.path("messageType").asText()))) {
            payload = cipher.decrypt(row.getEnterpriseId(), "outbound-rendered", row.getId(), row.getRenderedPayloadEncrypted());
        }
        var group = conversations.require(row.getEnterpriseId(), row.getConversationId());
        var current = replyPolicy.prepare(row, payload);
        return new Prepared(app, row.getFirstSubmittedAt() == null ? platforms.adapter(app.providerCode()).present(current) : current,
            group.getPlatformScopeKey(), group.getExternalConversationId());
    }

    @Transactional
    public void touch(ChatSendLease lease) {
        var row = requireOwned(lease);
        authorize(row);
        changed(messages.update(guard(lease).set(ChatOutboundMessageRow::getLeaseUntil, clock.instant().plusSeconds(60))));
    }

    public ChatPlatformAdapter.UploadFile uploadFile(ChatSendLease lease) {
        var row = requireOwned(lease);
        authorize(row);
        return files.file(row, cipher.decrypt(row.getEnterpriseId(), "outbound", row.getId(), row.getPayloadEncrypted()));
    }

    @Transactional
    public void uploaded(ChatSendLease lease, String key) {
        var row = requireOwned(lease);
        var app = authorize(row);
        if (row.getFirstSubmittedAt() != null || row.getRenderedPayloadEncrypted() != null || !row.getStatus().equals("leased")) {
            throw expired();
        }
        if (key == null || !key.matches("[A-Za-z0-9_./=-]{1,512}")) {
            throw new IllegalArgumentException("平台文件编号无效");
        }
        var payload = (ObjectNode) cipher.decrypt(row.getEnterpriseId(), "outbound", row.getId(), row.getPayloadEncrypted());
        files.file(row, payload);
        payload.putObject("content").put(app.providerCode().equals("feishu") ? "file_key" : "media_id", key);
        changed(messages.update(guard(lease).set(ChatOutboundMessageRow::getRenderedPayloadEncrypted,
            cipher.encrypt(row.getEnterpriseId(), "outbound-rendered", row.getId(), payload)).set(ChatOutboundMessageRow::getRenderedHash, json.hash(payload))
            .set(ChatOutboundMessageRow::getPreparedAt, clock.instant())));
    }

    @Transactional
    public void begin(ChatSendLease lease) {
        begin(lease, prepare(lease).payload());
    }

    @Transactional
    public void begin(ChatSendLease lease, JsonNode preparedPayload) {
        enterprises.lockEnterprise(lease.enterpriseId()).orElseThrow();
        var row = requireOwned(lease);
        if (!row.getStatus().equals("leased")) {
            throw expired();
        }
        var app = authorize(row);
        replyPolicy.verify(row, preparedPayload);
        if (row.getFirstSubmittedAt() != null && !canRepeat(row, platforms.adapter(app.providerCode()).deduplicationWindow())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_UNKNOWN", "消息可能已经发送，不能再次发送。");
        }
        var now = clock.instant();
        var rendered = row.getFirstSubmittedAt() == null
            ? cipher.encrypt(lease.enterpriseId(), "outbound-rendered", row.getId(), preparedPayload) : row.getRenderedPayloadEncrypted();
        int number = row.getAttemptCount() + 1;
        changed(messages.update(guard(lease).eq(ChatOutboundMessageRow::getStatus, "leased")
            .set(ChatOutboundMessageRow::getStatus, "sending").set(ChatOutboundMessageRow::getAttemptCount, number)
            .set(ChatOutboundMessageRow::getFirstSubmittedAt, row.getFirstSubmittedAt() == null ? now : row.getFirstSubmittedAt())
            .set(ChatOutboundMessageRow::getRenderedPayloadEncrypted, rendered)
            .set(ChatOutboundMessageRow::getRenderedHash, row.getFirstSubmittedAt() == null ? json.hash(preparedPayload) : row.getRenderedHash())
            .set(ChatOutboundMessageRow::getPreparedAt, row.getPreparedAt() == null ? now : row.getPreparedAt())
            .set(ChatOutboundMessageRow::getLeaseUntil, now.plusSeconds(60)).set(ChatOutboundMessageRow::getUpdatedAt, now)));
        var attempt = ChatRecords.initialize(new ChatOutboundAttemptRow(), lease.enterpriseId(), now);
        attempt.setOutboundId(row.getId());
        attempt.setAttemptNo(number);
        attempt.setStartedAt(now);
        attempt.setSubmittedAt(now);
        attempts.insert(attempt);
    }

    @Transactional
    public void complete(ChatSendLease lease, ChatPlatformAdapter.SendReceipt receipt) {
        enterprises.lockEnterprise(lease.enterpriseId()).orElseThrow();
        var row = owned(lease);
        if (row == null || !row.getStatus().equals("sending")) {
            return;
        }
        var result = receipt.result();
        var group = conversations.require(row.getEnterpriseId(), row.getConversationId());
        boolean retry = row.getAttemptCount() < 5 && (result.outcome() == Outcome.RETRYABLE_FAILURE || result.outcome() == Outcome.TOKEN_EXPIRED
            || result.outcome() == Outcome.UNKNOWN && canRepeat(row, platforms.adapter(group.getPlatformCode()).deduplicationWindow()));
        String status = result.outcome() == Outcome.ACCEPTED ? "sent" : retry ? "retry_wait" : result.outcome() == Outcome.UNKNOWN ? "unknown" : "failed";
        long wait = Math.min(3600, Math.max(1, result.retryAfterSeconds() == null ? 1L << Math.min(4, row.getAttemptCount() - 1) : result.retryAfterSeconds()));
        changed(messages.update(guard(lease).set(ChatOutboundMessageRow::getStatus, status)
            .set(Set.of(Outcome.RETRYABLE_FAILURE, Outcome.TOKEN_EXPIRED, Outcome.PERMANENT_FAILURE).contains(result.outcome()), ChatOutboundMessageRow::getFirstSubmittedAt, null)
            .set(ChatOutboundMessageRow::getPlatformMessageId, result.messageId()).set(ChatOutboundMessageRow::getPlatformThreadId, receipt.threadId())
            .set(ChatOutboundMessageRow::getErrorCode, result.errorCode()).set(ChatOutboundMessageRow::getNextAttemptAt, retry ? clock.instant().plusSeconds(wait) : null)
            .set(ChatOutboundMessageRow::getLeaseOwner, null).set(ChatOutboundMessageRow::getLeaseUntil, null)
            .set(ChatOutboundMessageRow::getUpdatedAt, clock.instant()).setIncrBy(ChatOutboundMessageRow::getRevision, 1)));
        changed(attempts.update(new LambdaUpdateWrapper<ChatOutboundAttemptRow>().eq(ChatOutboundAttemptRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatOutboundAttemptRow::getOutboundId, row.getId()).eq(ChatOutboundAttemptRow::getAttemptNo, row.getAttemptCount())
            .set(ChatOutboundAttemptRow::getOutcome, result.outcome().name()).set(ChatOutboundAttemptRow::getFinishedAt, clock.instant())
            .set(ChatOutboundAttemptRow::getHttpStatus, result.httpStatus())
            .set(ChatOutboundAttemptRow::getPlatformCode, result.providerCode()).set(ChatOutboundAttemptRow::getDiagnosticId, result.traceId())
            .set(ChatOutboundAttemptRow::getErrorSummary, result.summary()).set(ChatOutboundAttemptRow::getRetryAfterMs, wait * 1000)
            .set(ChatOutboundAttemptRow::getResponseMessageId, result.messageId())));
        boolean probe = Set.of("send_probe", "file_probe").contains(row.getKind());
        if (result.outcome() == Outcome.ACCEPTED || probe && status.equals("failed")) {
            var bot = bots.require(row.getEnterpriseId(), row.getBotBindingId());
            var payload = cipher.decrypt(row.getEnterpriseId(), "outbound", row.getId(), row.getPayloadEncrypted());
            if (bot.getRevision() == payload.path("bindingRevision").asLong()
                && bots.currentCredentials(bot, payload.path("credentialRevision").asLong())) {
                var evidence = Map.<String, Object>of("status", result.outcome() == Outcome.ACCEPTED ? "passed" : "failed", "checkedAt", clock.instant().toString(),
                    "outboundId", row.getId(), "bindingRevision", bot.getRevision(), "credentialRevision", payload.path("credentialRevision").asLong());
                capabilities.evidence(bot, payload.path("messageType").asText().equals("file") ? "uploadFile" : "sendText", evidence);
                if (result.outcome() == Outcome.ACCEPTED && payload.hasNonNull("replyToMessageId")) {
                    capabilities.evidence(bot, "threadReply", evidence);
                }
            }
        }
    }

    @Transactional
    public void defer(ChatSendLease lease, Duration wait, String error, boolean terminal) {
        var row = owned(lease);
        if (row == null || !row.getStatus().equals("leased")) {
            return;
        }
        boolean preparationFailure = !terminal && !Set.of("CHAT_RATE_LIMITED", "CHAT_REPLY_MODE_CHANGED").contains(error);
        int number = row.getAttemptCount() + (preparationFailure ? 1 : 0);
        boolean exhausted = preparationFailure && number >= 5;
        changed(messages.update(guard(lease).set(ChatOutboundMessageRow::getStatus, error.equals("CHAT_SEND_UNKNOWN") ? "unknown" : terminal ? "cancelled" : exhausted ? "failed" : "retry_wait")
            .set(ChatOutboundMessageRow::getAttemptCount, number)
            .set(ChatOutboundMessageRow::getNextAttemptAt, terminal || exhausted ? null : clock.instant().plus(wait))
            .set(ChatOutboundMessageRow::getErrorCode, error).set(ChatOutboundMessageRow::getLeaseOwner, null)
            .set(ChatOutboundMessageRow::getLeaseUntil, null).set(ChatOutboundMessageRow::getUpdatedAt, clock.instant())));
        if (preparationFailure) {
            var attempt = ChatRecords.initialize(new ChatOutboundAttemptRow(), lease.enterpriseId(), clock.instant());
            attempt.setOutboundId(row.getId());
            attempt.setAttemptNo(number);
            attempt.setStartedAt(clock.instant());
            attempt.setFinishedAt(clock.instant());
            attempt.setOutcome("RETRYABLE_FAILURE");
            attempt.setPlatformCode(error);
            attempt.setErrorSummary("消息未能发送，请重试。");
            attempts.insert(attempt);
        }
    }

    private IntegrationApplication authorize(ChatOutboundMessageRow row) {
        var bot = bots.require(row.getEnterpriseId(), row.getBotBindingId());
        var app = bots.application(bot);
        var group = conversations.require(row.getEnterpriseId(), row.getConversationId());
        var member = conversations.member(row.getEnterpriseId(), row.getConversationId(), row.getBotBindingId());
        var payload = cipher.decrypt(row.getEnterpriseId(), "outbound", row.getId(), row.getPayloadEncrypted());
        if (!ChatPlatformRegistry.supportsProvider(app.providerCode()) || !bot.getStatus().equals("enabled") || !group.getStatus().equals("enabled")
            || member == null || !member.getMembershipStatus().equals("present") || !Boolean.TRUE.equals(member.getSendEnabled())
            || !member.getExternalChatId().equals(payload.path("receiveId").asText())
            || app.credentialRevision() != payload.path("credentialRevision").asLong()) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_FORBIDDEN", "机器人或原会话的发送设置已发生变化。");
        }
        if (row.getTaskId() != null) {
            var task = access.require(row.getEnterpriseId(), row.getTaskId());
            access.actor(task);
            if (!ChatReplyPolicy.recipientMatches(task, group) || task.getStatus().equals("cancelling")
                || task.getStatus().equals("cancelled") && !row.getKind().equals("cancelled")
                || !task.getInputRevision().equals(row.getInputRevision())) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_CANCELLED", "任务或要求已发生变化，旧消息不再发送。");
            }
            var role = participants.selectOne(new LambdaQueryWrapper<CollaborationParticipantRow>().eq(CollaborationParticipantRow::getEnterpriseId, row.getEnterpriseId())
                .eq(CollaborationParticipantRow::getTaskId, row.getTaskId()).eq(CollaborationParticipantRow::getBotBindingId, row.getBotBindingId()));
            if (role == null) {
                throw CollaborationAccessService.roleUnavailable();
            }
            access.requireRole(task, role);
        }
        if (payload.path("messageType").asText().equals("file")) {
            if (row.getPreparedAt() != null && row.getPreparedAt().plusSeconds(172800).isBefore(clock.instant())) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_FILE_EXPIRED", "平台临时文件已过期，请从群策查看完整成果。");
            }
            files.file(row, payload);
        }
        return app;
    }

    private boolean canRepeat(ChatOutboundMessageRow row, Duration window) {
        return !window.isZero() && row.getFirstSubmittedAt() != null
            && row.getFirstSubmittedAt().plus(window).minusSeconds(30).isAfter(clock.instant());
    }

    private ChatOutboundMessageRow requireOwned(ChatSendLease lease) {
        var row = owned(lease);
        if (row == null) {
            throw expired();
        }
        return row;
    }

    private ChatOutboundMessageRow owned(ChatSendLease lease) {
        return messages.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatOutboundMessageRow::getId, lease.outboundId()).eq(ChatOutboundMessageRow::getLeaseOwner, lease.owner())
            .eq(ChatOutboundMessageRow::getLeaseVersion, lease.version()).gt(ChatOutboundMessageRow::getLeaseUntil, clock.instant()));
    }

    private LambdaUpdateWrapper<ChatOutboundMessageRow> guard(ChatSendLease lease) {
        return new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, lease.enterpriseId())
            .eq(ChatOutboundMessageRow::getId, lease.outboundId()).eq(ChatOutboundMessageRow::getLeaseVersion, lease.version())
            .eq(ChatOutboundMessageRow::getLeaseOwner, lease.owner()).gt(ChatOutboundMessageRow::getLeaseUntil, clock.instant());
    }

    private static void changed(int count) {
        if (count != 1) {
            throw expired();
        }
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_LEASE_EXPIRED", "发送工作已由其他进程接管。");
    }

    public record Prepared(IntegrationApplication application, JsonNode payload, String platformScope, String conversation) {
    }
}
