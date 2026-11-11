package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 只根据已确定的结果和实际确认记录提醒本人，不复制输入、工具参数或执行错误正文。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ExecutionNotificationService {
    private static final Duration COMPLETION_NOTICE_DELAY = Duration.ofSeconds(5);
    private final NotificationDeliveryService notifications;
    private final ConversationMapper conversations;
    private final ScheduleMapper schedules;

    public ExecutionNotificationService(NotificationDeliveryService notifications, ConversationMapper conversations,
                                        ScheduleMapper schedules) {
        this.notifications = notifications;
        this.conversations = conversations;
        this.schedules = schedules;
    }

    public void finished(RunRecord run) {
        if (run.mode().equals("preview")) {
            return;
        }
        boolean completed = run.status().equals("completed");
        // 协作岗位的一轮结束不等于总任务结束，最终答复由原聊天的成果发送流程交付。
        if (completed && "collaboration".equals(run.executionConfig().path("sourceType").asText())) {
            return;
        }
        if (!run.status().equals("failed") && (!completed || !notifications.completionEnabled(run.userId()))) {
            return;
        }
        if (run.mode().equals("interactive") && "EXECUTION_ACCESS_REVOKED".equals(run.errorCode())) {
            revokedNotice(run);
            return;
        }
        // 先给正在查看对话的页面确认结果的机会，未收到确认时照常发送。
        var notice = new Notice("run:" + run.id() + ":" + run.status(), "execution",
            name(run) + (completed ? Messages.text("text.agent.agentHistoryMessageMapper.toolStatus.1") : Messages.text("text.agent.agentHistoryMessageMapper.toolStatus.6")), Messages.text("text.execution.executionNotificationService.finished.1"), "conversation",
            run.conversationId());
        notifications.enqueue(run.enterpriseId(), run.userId(), notice,
            completed ? COMPLETION_NOTICE_DELAY : Duration.ZERO);
    }

    public void waiting(RunRecord run, String approvalId) {
        enqueue(run, "approval:" + approvalId, "approval", Messages.text("text.execution.executionNotificationService.waiting.1", name(run)),
            Messages.text("text.execution.executionNotificationService.waiting.2"));
    }

    public void accessRevoked(RunRecord run) {
        if (run.terminal() || run.status().equals("cancelling")) {
            return;
        }
        revokedNotice(run);
    }

    private void revokedNotice(RunRecord run) {
        if (!run.mode().equals("interactive")) {
            return;
        }
        enqueue(run, "run:" + run.id() + ":access-revoked", "permission", Messages.text("text.execution.executionNotificationService.revokedNotice.1"),
            Messages.text("text.execution.executionNotificationService.revokedNotice.2"));
    }

    private void enqueue(RunRecord run, String event, String category, String title, String body) {
        notifications.enqueue(run.enterpriseId(), run.userId(),
            new Notice(event, category, title, body, "conversation", run.conversationId()));
    }

    private String name(RunRecord run) {
        if (run.mode().equals("scheduled") || run.mode().equals("manual_schedule")) {
            return "“" + schedules.find(run.enterpriseId(), run.userId(),
                    run.executionConfig().path("scheduleId").asText(), false, true)
                .orElseThrow(() -> new IllegalStateException(Messages.text("error.execution.executionNotificationService.name.1"))).name() + "”";
        }
        return Messages.text("text.execution.executionNotificationService.name.1", conversations.find(run.enterpriseId(), run.userId(), run.conversationId(), false)
            .orElseThrow(() -> new IllegalStateException(Messages.text("error.execution.executionNotificationService.name.2"))).agentName());
    }
}
