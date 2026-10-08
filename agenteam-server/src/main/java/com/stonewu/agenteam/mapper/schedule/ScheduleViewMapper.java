package com.stonewu.agenteam.mapper.schedule;

import com.stonewu.agenteam.model.schedule.entity.ScheduleOccurrenceRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.response.ScheduleOccurrenceView;
import com.stonewu.agenteam.model.schedule.response.ScheduleView;
import com.stonewu.agenteam.model.schedule.response.ScheduleActionView;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Locale;

/**
 * 公开时间、员工版本和真实状态，不向页面展示内部暂停代码。
 */
@Component
public class ScheduleViewMapper {
    private final ResourceJson json;

    public ScheduleViewMapper(ResourceJson json) {
        this.json = json;
    }

    public ScheduleView view(ScheduleRecord value, ScheduleOccurrenceRecord active, ScheduleOccurrenceRecord latest, ScheduleActionView action) {
        var rule = value.rule();
        return new ScheduleView(value.id(), Long.toString(value.revision()), text(value.createdAt()),
            text(value.updatedAt()), value.name(), value.hireId(), value.agentVersionId(),
            value.inputText(), rule.frequency().name().toLowerCase(Locale.ROOT),
            rule.date() == null ? null : rule.date().toString(), rule.time().toString(),
            rule.weekdays().stream().map(DayOfWeek::getValue).sorted().toList(), rule.monthDay(),
            rule.timezone().getId(), value.enabled(), value.maxRetries(),
            text(value.nextRunAt()), value.pauseReason() == null ? null : reason(value.pauseReason()),
            value.activeOccurrenceId(), active == null ? null : view(active), latest == null ? null : view(latest),
            value.agentId(), value.agentName(), value.agentVersionNo(), value.agentIcon(), value.agentColor(), action);
    }

    public ScheduleOccurrenceView view(ScheduleOccurrenceRecord value) {
        String message = value.errorMessage() == null ? value.errorSummary() != null ? value.errorSummary() : value.reasonCode() == null ? null : reason(
            value.reasonCode()) : value.errorMessage();
        if (message != null && message.length() > 500) {
            message = message.substring(0,
                message.offsetByCodePoints(0, Math.min(500, message.codePointCount(0, message.length()))));
        }
        return new ScheduleOccurrenceView(value.id(), value.scheduleId(), value.triggerKind(),
            text(value.scheduledFor()), value.runId(), value.conversationId(),
            value.status(), value.reasonCode(), message, value.attemptCount(), text(value.startedAt()),
            text(value.finishedAt()), value.actionType(), value.actionSchemaVersion(),
            value.scheduleRevision() == null ? null : value.scheduleRevision().toString(), value.snapshotOrigin(),
            value.actionSnapshotJson() == null ? null : json.object(json.read(value.actionSnapshotJson())),
            json.object(json.read(value.actionResultJson())));
    }

    public String reason(String code) {
        return switch (code) {
            case "SCHEDULE_RECONFIRM_REQUIRED" -> "此计划超过三十天没有检查，已暂停。请确认时间后重新启用。";
            case "SCHEDULE_PREVIOUS_ACTIVE" -> "上一次任务尚未结束，本次已跳过。";
            case "SCHEDULE_MISSED" -> "本次已错过执行时间。";
            case "QUOTA_EXCEEDED" -> "本月适用的执行次数不足，本次未执行。";
            case "CONCURRENCY_LIMIT" -> "同时执行的任务已达到上限，本次未执行。";
            case "SCHEDULE_OWNER_UNAVAILABLE" -> "创建人的账号或企业成员资格已不可用，计划已暂停。";
            case "SCHEDULE_HIRE_UNAVAILABLE" -> "员工雇佣已暂停或解除，计划已暂停。";
            case "SCHEDULE_RESOURCE_UNAVAILABLE" -> "员工版本或所需能力已不可用，计划已暂停。";
            case "SCHEDULE_PERMISSION_DENIED" -> "执行所需权限已变化，计划已暂停。";
            case "SCHEDULE_CANCELLED" -> "已停止尚未开始的操作。";
            case "SCHEDULE_NOTIFICATION_EXPIRED" -> "本次通知已超过发送有效期。";
            case "SCHEDULE_NOTIFICATION_PARTIAL" -> "部分通知未能发送，请查看各接收人的结果。";
            case "SCHEDULE_NOTIFICATION_UNKNOWN" -> "部分平台请求尚不能确认是否被接受，请查看发送详情。";
            case "SCHEDULE_NOTIFICATION_FAILED" -> "本次通知发送失败，请查看发送详情。";
            case "SCHEDULE_NOTIFICATION_BLOCKED" -> "本次通知的接收条件已变化，未能发送。";
            case "SCHEDULE_PREPARATION_FAILED" -> "本次操作准备失败，请检查后重新执行。";
            case "SCHEDULE_NOTIFICATIONS_DISABLED" -> "通知计划已暂时关闭，请稍后重新启用。";
            default -> "本次无法继续执行，请检查计划和任务详情。";
        };
    }

    private String text(Instant value) {
        return value == null ? null : value.toString();
    }
}
