package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.notification.ExecutionResultNoticeMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.notification.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class ExecutionResultViewedService {
    private final ConversationQueryService queries;
    private final NotificationService notifications;
    private final ExecutionResultNoticeMapper notices;
    private final Clock clock;

    public ExecutionResultViewedService(ConversationQueryService queries, NotificationService notifications,
                                        ExecutionResultNoticeMapper notices, Clock clock) {
        this.queries = queries;
        this.notifications = notifications;
        this.notices = notices;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void viewed(AuthContext actor, String runId) {
        // 与发送通知保持相同的成员加锁顺序，并发发送和确认最终只能留下已读通知或没有通知。
        notifications.authorize(actor);
        var run = queries.run(actor, runId);
        if (!"completed".equals(run.status()) || "preview".equals(run.mode())) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_RESULT_NOT_COMPLETED",
                "任务尚未完成，请稍后确认结果。");
        }
        notices.viewed(actor.enterpriseId(), actor.userId(), run.id(), run.conversationId(), clock.instant());
    }
}
