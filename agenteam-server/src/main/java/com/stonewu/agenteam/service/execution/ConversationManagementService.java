package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.request.ConversationUpdateInput;
import com.stonewu.agenteam.model.execution.request.MessageFeedbackInput;
import com.stonewu.agenteam.model.execution.response.ConversationView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Set;

/**
 * 本人会话管理和反馈与执行提交共用会话行锁，归档和删除不能绕过活动任务检查。
 */
@Service
public class ConversationManagementService {
    private final EnterpriseAuthorizationService authorization;
    private final ConversationMapper conversations;
    private final ExecutionMessageMapper messages;
    private final ConversationQueryService queries;
    private final Clock clock;

    public ConversationManagementService(EnterpriseAuthorizationService authorization, ConversationMapper conversations,
                                         ExecutionMessageMapper messages, ConversationQueryService queries,
                                         Clock clock) {
        this.authorization = authorization;
        this.conversations = conversations;
        this.messages = messages;
        this.queries = queries;
        this.clock = clock;
    }

    public ConversationRecord authorize(AuthContext actor, String id) {
        authorization.lockAndRequire(actor, "conversation.manage");
        return conversations.find(actor.enterpriseId(), actor.userId(), id, true).filter(queries::retained)
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    @Transactional
    public ConversationView update(AuthContext actor, String id, ConversationUpdateInput input, long revision) {
        var row = authorize(actor, id);
        revision(row, revision);
        if (row.status().equals("deleted")) {
            throw ResourceAuthorizationService.unavailable();
        }
        String title = input.title() == null ? null : ResourceInput.text(input.title(), "title", 100, true);
        if (title != null && title.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("title", "对话名称不能包含换行或控制字符。");
        }
        if (input.status() != null && !Set.of("active", "archived").contains(input.status())) {
            throw ApiException.invalidField("status", "请选择有效的对话状态。");
        }
        String status = input.status() == null ? row.status() : input.status();
        if (status.equals("archived")) {
            inactive(row);
        }
        if (input.approvalPolicy() != null) {
            authorization.lockAndRequire(actor, "agent.run");
            if (!Set.of("default", "auto_approve", "full_access").contains(input.approvalPolicy())) {
                throw ApiException.invalidField("approvalPolicy", "请选择有效的工具审批策略。");
            }
            if (!row.mode().equals("normal") || !row.status().equals("active")) {
                throw ApiException.invalidField("approvalPolicy", "只能修改可继续对话的工具审批策略。");
            }
            if (row.activeRunId() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_BUSY",
                    "请等待当前任务结束，或先停止任务，再修改工具审批策略。");
            }
        }
        if (title != null || input.favorite() != null || !status.equals(
            row.status()) || input.approvalPolicy() != null) {
            conversations.update(row, title, input.favorite(), status, input.approvalPolicy(), clock.instant());
        }
        return current(actor, id);
    }

    @Transactional
    public void delete(AuthContext actor, String id, long revision) {
        var row = authorize(actor, id);
        revision(row, revision);
        inactive(row);
        if (!row.status().equals("deleted")) {
            conversations.update(row, null, null, "deleted", clock.instant());
        }
    }

    @Transactional
    public ConversationView restore(AuthContext actor, String id, long revision) {
        var row = authorize(actor, id);
        revision(row, revision);
        if (!row.status().equals("deleted")) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_NOT_DELETED", "此对话没有被删除，请重新加载列表。");
        }
        if (row.deletedAt() == null || !row.deletedAt().isAfter(clock.instant().minus(Duration.ofDays(30)))) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_EXPIRED", "此对话已经超过可恢复时间。");
        }
        conversations.update(row, null, null, "active", clock.instant());
        return current(actor, id);
    }

    public String authorizeFeedback(AuthContext actor, String message) {
        authorization.lockAndRequire(actor, "conversation.view");
        String id = messages.ownedConversation(actor.enterpriseId(), actor.userId(), message)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        conversations.find(actor.enterpriseId(), actor.userId(), id, true)
            .filter(value -> !value.status().equals("deleted") && queries.retained(value))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return id;
    }

    @Transactional
    public void feedback(AuthContext actor, String id, MessageFeedbackInput input) {
        String conversation = authorizeFeedback(actor, id);
        var message = messages.find(actor.enterpriseId(), conversation, id).orElseThrow();
        if (!message.role().equals("assistant") || Set.of("pending", "streaming").contains(message.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "FEEDBACK_UNAVAILABLE", "请在任务结束后评价回复。");
        }
        if (input.value() != null && !Set.of("positive", "negative").contains(input.value())) {
            throw ApiException.invalidField("value", "请选择有效的反馈。");
        }
        String comment = ResourceInput.text(input.comment() == null ? "" : input.comment(), "comment", 500, false);
        messages.feedback(actor.enterpriseId(), actor.userId(), id, input.value(), comment, clock.instant());
    }

    private void revision(ConversationRecord row, long expected) {
        if (row.revision() != expected) {
            throw ApiException.versionConflict(row.revision());
        }
    }

    private void inactive(ConversationRecord row) {
        if (row.activeRunId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_BUSY",
                "请先停止或等待当前任务结束，再归档或删除对话。");
        }
    }

    private ConversationView current(AuthContext actor, String id) {
        return queries.view(actor, conversations.find(actor.enterpriseId(), actor.userId(), id, false).orElseThrow(),
            new HashMap<>());
    }
}
