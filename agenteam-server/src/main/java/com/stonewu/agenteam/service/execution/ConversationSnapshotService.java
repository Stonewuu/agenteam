package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.execution.LiveEventCache;
import com.stonewu.agenteam.mapper.execution.LiveSnapshotMapper;
import com.stonewu.agenteam.mapper.execution.RunAttemptMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.response.ConversationSnapshotView;
import com.stonewu.agenteam.model.execution.response.MessageView;
import com.stonewu.agenteam.model.execution.response.RunStepHistoryView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 每次重试重新开启数据库读取事务，再与同一时刻取得的 Redis 累计内容组合。
 */
@Service
public class ConversationSnapshotService {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationSnapshotService.class);
    private static final int BATCH_STEP_RUN_LIMIT = 50;
    private static final int BATCH_STEP_ROW_LIMIT = 1000;
    private final ConversationQueryService queries;
    private final LiveEventCache cache;
    private final LiveEventDelivery delivery;
    private final LiveSnapshotMapper mapper;
    private final RunMapper runs;
    private final RunAttemptMapper attempts;
    private final RunStepMapper steps;

    public ConversationSnapshotService(ConversationQueryService queries, LiveEventCache cache,
                                       LiveEventDelivery delivery,
                                       LiveSnapshotMapper mapper, RunMapper runs, RunAttemptMapper attempts,
                                       RunStepMapper steps) {
        this.queries = queries;
        this.cache = cache;
        this.delivery = delivery;
        this.mapper = mapper;
        this.runs = runs;
        this.attempts = attempts;
        this.steps = steps;
    }

    public ConversationSnapshotView read(AuthContext actor, String id) {
        var base = queries.snapshotBase(actor, id, false);
        if (!base.conversation().liveEvents()) {
            var decorated = queries.decorateMessages(actor, id, base.snapshot().messages());
            var snapshot = base.snapshot();
            return new ConversationSnapshotView(snapshot.conversation(), decorated, snapshot.activeRun(),
                snapshot.lastSequence(), snapshot.hasOlderMessages(), snapshot.nextBeforeMessageId(),
                snapshot.attachmentsEnabled(), snapshot.protocolVersion(), snapshot.streamCursor(),
                snapshot.liveSteps(), snapshot.approvals(), snapshot.queuedMessages(),
                stepHistory(actor.enterpriseId(), decorated));
        }
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                if (attempt > 0) {
                    base = queries.snapshotBase(actor, id, false);
                }
                delivery.ensure(base.conversation());
                var live = cache.snapshot(actor.enterpriseId(), id).orElseThrow();
                if (live.covers(base.conversation().lastSequence())) {
                    var attemptIds = live.objects().entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith("step:"))
                        .map(entry -> entry.getValue().path("step").path("attemptId").asText())
                        .collect(Collectors.toSet());
                    var owners = attempts.outputMessages(actor.enterpriseId(), actor.userId(), id, attemptIds);
                    var merged = mapper.merge(base.snapshot(), live, owners);
                    var decorated = queries.decorateMessages(actor, id, merged.messages());
                    return new ConversationSnapshotView(merged.conversation(),
                        decorated,
                        merged.activeRun(), merged.lastSequence(), merged.hasOlderMessages(),
                        merged.nextBeforeMessageId(),
                        merged.attachmentsEnabled(), 2, merged.streamCursor(), merged.liveSteps(), merged.approvals(),
                        merged.queuedMessages(), stepHistory(actor.enterpriseId(), decorated));
                }
                if (live.appliedDatabaseVersion() < base.conversation().lastSequence()) {
                    var active = base.run();
                    if (active == null) {
                        var latest = base.snapshot().messages().stream().filter(message -> message.runId() != null)
                            .reduce((left, right) -> right);
                        active = latest.flatMap(message -> runs.find(actor.enterpriseId(), message.runId(), false))
                            .orElse(null);
                    }
                    if (active != null) {
                        delivery.recover(active);
                    }
                }
            }
            throw new IllegalStateException(Messages.text("error.execution.conversationSnapshotService.read.1"));
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            logUnavailable(id, unavailable);
            // 已保存历史仍可读取；没有实时位置时，客户端延迟重新读取而不猜测编号。
            var decorated = queries.decorateMessages(actor, id, base.snapshot().messages());
            var fallback = mapper.unavailable(base.snapshot());
            return new ConversationSnapshotView(fallback.conversation(), decorated, fallback.activeRun(),
                fallback.lastSequence(), fallback.hasOlderMessages(), fallback.nextBeforeMessageId(),
                fallback.attachmentsEnabled(), fallback.protocolVersion(), fallback.streamCursor(),
                fallback.liveSteps(), fallback.approvals(), fallback.queuedMessages(),
                stepHistory(actor.enterpriseId(), decorated));
        }
    }

    private Map<String, RunStepHistoryView> stepHistory(String enterpriseId, List<MessageView> messages) {
        var runIds = messages.stream()
            .filter(message -> "assistant".equals(message.role()) && message.runId() != null)
            .map(MessageView::runId)
            .filter(Objects::nonNull)
            .distinct()
            .limit(BATCH_STEP_RUN_LIMIT)
            .toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        var groupedAttempts = attempts.listByRuns(enterpriseId, runIds);
        var groupedSteps = steps.listByRuns(enterpriseId, runIds, BATCH_STEP_ROW_LIMIT);
        Map<String, RunStepHistoryView> history = new LinkedHashMap<>();
        for (String runId : runIds) {
            var stepBatch = groupedSteps.get(runId);
            history.put(runId, new RunStepHistoryView(
                stepBatch == null ? List.of() : stepBatch.steps(),
                groupedAttempts.getOrDefault(runId, List.of()),
                stepBatch != null && stepBatch.hasMore()));
        }
        return history;
    }

    private void logUnavailable(String conversation, RuntimeException failure) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            LOG.warn(Messages.log("log.execution.conversationSnapshotService.logUnavailable.1"), conversation,
                request.getAttribute(AuditEventService.REQUEST_ID_ATTRIBUTE), request.getMethod(),
                request.getRequestURI(), failure);
        } else {
            LOG.warn(Messages.log("log.execution.conversationSnapshotService.logUnavailable.2"), conversation, failure);
        }
    }
}
