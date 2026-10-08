package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.todo.TodoDefinitionMapper;
import com.stonewu.agenteam.mapper.todo.TodoRelationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.todo.entity.TodoSource;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * 来源沿用本人会话读取资格；允许转交待办，不转交原会话。
 */
@Service
public class TodoSourceService {
    private final ConversationQueryService conversations;
    private final ExecutionMessageMapper messages;
    private final RunMapper runs;
    private final TodoRelationMapper relations;
    private final TodoDefinitionMapper input;

    public TodoSourceService(ConversationQueryService conversations, ExecutionMessageMapper messages, RunMapper runs,
                             TodoRelationMapper relations, TodoDefinitionMapper input) {
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.relations = relations;
        this.input = input;
    }

    public TodoSource create(AuthContext actor, TodoWriteRequest value) {
        if (value.sourceType() == null || !Set.of("manual", "message", "workflow").contains(value.sourceType())) {
            throw ApiException.invalidField("sourceType", "请选择有效的待办来源。");
        }
        if (value.sourceType().equals("manual")) {
            if (value.sourceConversationId() != null || value.sourceMessageId() != null || value.sourceRunId() != null) {
                throw immutable();
            }
            return new TodoSource("manual", null, null, null);
        }
        String conversationId = input.identifier(value.sourceConversationId(), "sourceConversationId");
        conversations.readable(actor, conversationId);
        String messageId = value.sourceMessageId() == null ? null : input.identifier(value.sourceMessageId(),
            "sourceMessageId");
        String runId = value.sourceRunId() == null ? null : input.identifier(value.sourceRunId(), "sourceRunId");
        if (value.sourceType().equals("message") && messageId == null) {
            throw ApiException.invalidField("sourceMessageId", "请选择作为来源的消息。");
        }
        if (messageId != null) {
            var message = messages.find(actor.enterpriseId(), conversationId, messageId)
                .orElseThrow(ResourceAuthorizationService::unavailable);
            if (runId != null && !Objects.equals(message.runId(), runId)) {
                throw ResourceAuthorizationService.unavailable();
            }
            runId = message.runId();
        }
        if (runId != null) {
            var run = runs.find(actor.enterpriseId(), runId, false)
                .orElseThrow(ResourceAuthorizationService::unavailable);
            if (!run.userId().equals(actor.userId()) || !run.conversationId().equals(conversationId)) {
                throw ResourceAuthorizationService.unavailable();
            }
        }
        if (value.sourceType().equals("workflow") && (runId == null || !relations.hasStartedWorkflow(
            actor.enterpriseId(), runId))) {
            throw ApiException.invalidField("sourceRunId", "请选择已经实际执行的工作流作为来源。");
        }
        return new TodoSource(value.sourceType(), conversationId, messageId, runId);
    }

    public void unchanged(TodoSource before, TodoWriteRequest input) {
        if (!Objects.equals(before.type(),
            input.sourceType()) || input.sourceConversationId() != null || input.sourceMessageId() != null || input.sourceRunId() != null) {
            throw immutable();
        }
    }

    public boolean accessible(AuthContext actor, TodoSource source) {
        return source.conversationId() != null && conversations.canRead(actor, source.conversationId());
    }

    private ApiException immutable() {
        return ApiException.invalidField("sourceType", "待办来源不能自行改写，请从原消息或工作流创建。");
    }
}
