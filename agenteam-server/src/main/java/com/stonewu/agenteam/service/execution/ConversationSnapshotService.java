package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.LiveEventCache;
import com.stonewu.agenteam.mapper.execution.LiveSnapshotMapper;
import com.stonewu.agenteam.mapper.execution.RunAttemptMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.response.ConversationSnapshotView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * 每次重试重新开启数据库读取事务，再与同一时刻取得的 Redis 累计内容组合。
 */
@Service
public class ConversationSnapshotService {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationSnapshotService.class);
    private final ConversationQueryService queries;
    private final LiveEventCache cache;
    private final LiveEventDelivery delivery;
    private final LiveSnapshotMapper mapper;
    private final RunMapper runs;
    private final RunAttemptMapper attempts;

    public ConversationSnapshotService(ConversationQueryService queries, LiveEventCache cache,
                                       LiveEventDelivery delivery,
                                       LiveSnapshotMapper mapper, RunMapper runs, RunAttemptMapper attempts) {
        this.queries = queries;
        this.cache = cache;
        this.delivery = delivery;
        this.mapper = mapper;
        this.runs = runs;
        this.attempts = attempts;
    }

    public ConversationSnapshotView read(AuthContext actor, String id) {
        var base = queries.snapshotBase(actor, id);
        if (!base.conversation().liveEvents()) {
            return base.snapshot();
        }
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                if (attempt > 0) {
                    base = queries.snapshotBase(actor, id);
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
                    return new ConversationSnapshotView(merged.conversation(),
                        queries.decorateMessages(actor, id, merged.messages()),
                        merged.activeRun(), merged.lastSequence(), merged.hasOlderMessages(),
                        merged.nextBeforeMessageId(),
                        merged.attachmentsEnabled(), 2, merged.streamCursor(), merged.liveSteps(), merged.approvals());
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
            throw new IllegalStateException("历史消息与累计输出正在更新，需要重新读取");
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            logUnavailable(id, unavailable);
            // 已保存历史仍可读取；没有实时位置时，客户端延迟重新读取而不猜测编号。
            return mapper.unavailable(base.snapshot());
        }
    }

    private void logUnavailable(String conversation, RuntimeException failure) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            LOG.warn("累计输出暂时无法读取，会话编号 {}，请求编号 {}，请求方法 {}，请求路径 {}", conversation,
                request.getAttribute(AuditEventService.REQUEST_ID_ATTRIBUTE), request.getMethod(),
                request.getRequestURI(), failure);
        } else {
            LOG.warn("累计输出暂时无法读取，会话编号 {}", conversation, failure);
        }
    }
}
