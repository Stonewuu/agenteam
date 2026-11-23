package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.CommittedConversationChanges;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 每个事务只推进一次会话保存版本，展示变化在提交后统一发送，不另存数据库事件。
 */
@Service
public class ConversationChangePublisher {
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final ObjectMapper json;
    private final ResourceJson canonical;
    private final ApplicationEventPublisher notifications;

    public ConversationChangePublisher(ConversationMapper conversations, RunMapper runs, ObjectMapper json,
                                       ResourceJson canonical, ApplicationEventPublisher notifications) {
        this.conversations = conversations;
        this.runs = runs;
        this.json = json;
        this.canonical = canonical;
        this.notifications = notifications;
    }

    public ExecutionEvent append(RunRecord run, String type, Object payload, Instant now) {
        var transaction = transaction();
        String key = run.enterpriseId() + ":" + run.conversationId();
        var changes = transaction.conversations.computeIfAbsent(key, ignored -> {
            conversations.find(run.enterpriseId(), run.userId(), run.conversationId(), true).orElseThrow();
            return new Changes(run, conversations.advanceSequence(run.enterpriseId(), run.conversationId(), now));
        });
        if (changes.runs.add(run.id())) {
            runs.updateSequence(run.enterpriseId(), run.id(), changes.version);
        }
        ObjectNode body = json.valueToTree(payload);
        if (type.startsWith("run.")) {
            body.put("lastSequence", Long.toString(changes.version));
        }
        var event = new ExecutionEvent(1, UUID.randomUUID().toString(), run.enterpriseId(), run.conversationId(),
            run.id(), Long.toString(changes.version), now.toString(), type, canonical.object(body));
        changes.events.add(event);
        return event;
    }

    private PendingTransaction transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Conversation changes require an active transaction");
        }
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingTransaction pending && pending.publisher() == this) {
                return pending;
            }
        }
        var pending = new PendingTransaction();
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    private static final class Changes {
        private final RunRecord run;
        private final long version;
        private final Set<String> runs = new HashSet<>();
        private final List<ExecutionEvent> events = new ArrayList<>();

        private Changes(RunRecord run, long version) {
            this.run = run;
            this.version = version;
        }
    }

    private final class PendingTransaction implements TransactionSynchronization {
        private final Map<String, Changes> conversations = new LinkedHashMap<>();

        private ConversationChangePublisher publisher() {
            return ConversationChangePublisher.this;
        }

        @Override
        public void afterCommit() {
            for (var changes : conversations.values()) {
                notifications.publishEvent(new CommittedConversationChanges(changes.run, changes.version,
                    List.copyOf(changes.events)));
            }
        }
    }
}
