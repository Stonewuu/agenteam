package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.CommittedExecutionEvent;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 先保存规范化事件，数据库序号也是 Redis 分发时使用的编号。
 */
@Repository
public class ExecutionEventMapper {
    private final ExecutionEventSqlMapper statements;
    private final ObjectMapper json;
    private final ResourceJson canonical;
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final ExecutionEventStorageMapper storage;
    private final ApplicationEventPublisher notifications;

    public ExecutionEventMapper(ExecutionEventSqlMapper statements, ObjectMapper json, ResourceJson canonical,
                                ConversationMapper conversations, RunMapper runs, ExecutionEventStorageMapper storage,
                                ApplicationEventPublisher notifications) {
        this.statements = statements;
        this.json = json;
        this.canonical = canonical;
        this.conversations = conversations;
        this.runs = runs;
        this.storage = storage;
        this.notifications = notifications;
    }

    public ExecutionEvent append(RunRecord run, String type, Object payload, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("事件和消息必须在同一事务保存");
        }
        var conversation = conversations.find(run.enterpriseId(), run.userId(), run.conversationId(), true)
            .orElseThrow();
        long sequence = conversations.advanceSequence(run.enterpriseId(), run.conversationId(), now);
        long runSequence = Math.addExact(statements.appendAgentEvent(run.id()).stream().findFirst().orElse(0L), 1);
        JsonNode body = json.valueToTree(payload);
        if (type.startsWith("run.")) {
            ((ObjectNode) body).put("lastSequence", Long.toString(sequence));
        }
        var event = new ExecutionEvent(1, UUID.randomUUID().toString(), run.enterpriseId(), run.conversationId(),
            run.id(),
            Long.toString(sequence), now.toString(), type, canonical.object(body));
        storage.append(event, runSequence, now);
        runs.updateSequence(run.enterpriseId(), run.id(), sequence);
        notifications.publishEvent(new CommittedExecutionEvent(run, event, conversation.liveEvents()));
        return event;
    }

    public List<ExecutionEvent> after(String enterprise, String conversation, long after, int limit) {
        return statements.afterAgentEvent(enterprise, conversation, after, limit).stream()
            .flatMap(row -> storage.decode(row).stream())
            .filter(event -> Long.parseLong(event.sequence()) > after).limit(Math.max(0, limit)).toList();
    }

    public List<ExecutionEvent> unpublished(String enterprise, String conversation, int limit) {
        return statements.unpublishedAgentEvent(enterprise, conversation, limit).stream()
            .flatMap(row -> storage.decode(row).stream()).limit(Math.max(0, limit)).toList();
    }

    public void published(ExecutionEvent event, Instant now) {
        publishedThrough(event.enterpriseId(), event.conversationId(), Long.parseLong(event.sequence()), now);
    }

    public void publishedThrough(String enterprise, String conversation, long sequence, Instant now) {
        statements.publishedThroughAgentEvent(timestamp(now), enterprise, conversation, sequence);
    }

    public int removeExpired(Instant boundary) {
        return statements.removeExpiredAgentEvent(timestamp(boundary));
    }

    public record PendingConversation(String enterpriseId, String conversationId) {
    }

    public List<PendingConversation> pendingConversations(int limit) {
        return statements.pendingConversationsAgentEvent(limit).stream()
            .map(row -> new PendingConversation(row.getEnterpriseId(), row.getConversationId())).toList();
    }

    public long firstSequence(String enterprise, String conversation) {
        return after(enterprise, conversation, 0, 1).stream().mapToLong(event -> Long.parseLong(event.sequence()))
            .findFirst().orElse(0);
    }

}
