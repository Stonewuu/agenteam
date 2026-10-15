package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundAttemptMapper;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundAttemptRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 管理员只查发送状态，不读取任务正文；未知结果不能手动重复发送。 */
@Service
public class ChatOutboundQueryService {
    public record View(String id, String conversationId, String botId, String kind, String status, int attemptCount,
            String errorCode, String platformMessageId, long revision, Instant createdAt, Instant updatedAt) {
    }
    private final ChatOutboundMessageMapper messages;
    private final ChatOutboundAttemptMapper attempts;
    private final ListPagination pagination;
    private final Clock clock;
    public ChatOutboundQueryService(ChatOutboundMessageMapper messages, ChatOutboundAttemptMapper attempts, ListPagination pagination, Clock clock) {
        this.messages = messages;
        this.attempts = attempts;
        this.pagination = pagination;
        this.clock = clock;
    }

    public PageResponse<View> list(String user, String enterprise, String conversation, String cursor, Integer requested) {
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(user, enterprise, "chat/outbounds", conversation == null ? "" : conversation, "created_desc");
        var after = pagination.read(cursor, binding);
        var query = new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
            .eq(conversation != null, ChatOutboundMessageRow::getConversationId, conversation).orderByDesc(ChatOutboundMessageRow::getCreatedAt, ChatOutboundMessageRow::getId);
        if (after != null) {
            query.and(value -> value.lt(ChatOutboundMessageRow::getCreatedAt, after.time()).or(equal -> equal
                .eq(ChatOutboundMessageRow::getCreatedAt, after.time()).lt(ChatOutboundMessageRow::getId, after.id())));
        }
        var page = pagination.page(messages.selectPage(new Page<ChatOutboundMessageRow>(1, limit + 1, false), query).getRecords(), limit,
            binding, value -> new PagePosition(value.getCreatedAt(), value.getId()));
        return new PageResponse<>(page.items().stream().map(value -> new View(value.getId(), value.getConversationId(), value.getBotBindingId(),
            value.getKind(), value.getStatus(), value.getAttemptCount(), value.getErrorCode(), value.getPlatformMessageId(), value.getRevision(), value.getCreatedAt(), value.getUpdatedAt())).toList(),
            page.nextCursor(), page.hasMore());
    }

    @Transactional
    public Map<String, String> retry(String enterprise, String id, long revision) {
        var row = messages.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getId, id));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_SEND_UNAVAILABLE", "发送记录不存在。");
        }
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        var latest = attempts.selectPage(new Page<ChatOutboundAttemptRow>(1, 1, false), new LambdaQueryWrapper<ChatOutboundAttemptRow>()
            .eq(ChatOutboundAttemptRow::getEnterpriseId, enterprise).eq(ChatOutboundAttemptRow::getOutboundId, id).orderByDesc(ChatOutboundAttemptRow::getAttemptNo)).getRecords();
        if (!row.getStatus().equals("failed") || !latest.isEmpty() && !Set.of("PERMANENT_FAILURE", "RETRYABLE_FAILURE", "TOKEN_EXPIRED").contains(latest.getFirst().getOutcome())
            || row.getFirstSubmittedAt() != null && latest.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_RETRY_FORBIDDEN", "仅明确未被平台接受的失败消息可以重试；发送结果未知时请先核实。");
        }
        if (messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
            .eq(ChatOutboundMessageRow::getId, id).eq(ChatOutboundMessageRow::getRevision, revision).eq(ChatOutboundMessageRow::getStatus, "failed")
            .set(ChatOutboundMessageRow::getStatus, "pending").set(ChatOutboundMessageRow::getFirstSubmittedAt, null)
            .set(ChatOutboundMessageRow::getNextAttemptAt, clock.instant()).set(ChatOutboundMessageRow::getErrorCode, null)
            .setIncrBy(ChatOutboundMessageRow::getRevision, 1).setIncrBy(ChatOutboundMessageRow::getLeaseVersion, 1)) != 1) {
            throw ApiException.versionConflict(revision);
        }
        return Map.of("id", id, "status", "pending");
    }
}
