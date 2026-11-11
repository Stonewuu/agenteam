package com.stonewu.agenteam.mapper.schedule;

import com.stonewu.agenteam.service.i18n.Messages;

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
            case "SCHEDULE_RECONFIRM_REQUIRED" -> Messages.text("error.schedule.scheduleViewMapper.reason.1");
            case "SCHEDULE_PREVIOUS_ACTIVE" -> Messages.text("error.schedule.scheduleViewMapper.reason.2");
            case "SCHEDULE_MISSED" -> Messages.text("error.schedule.scheduleViewMapper.reason.3");
            case "QUOTA_EXCEEDED" -> Messages.text("error.schedule.scheduleViewMapper.reason.4");
            case "CONCURRENCY_LIMIT" -> Messages.text("error.schedule.scheduleViewMapper.reason.5");
            case "SCHEDULE_OWNER_UNAVAILABLE" -> Messages.text("error.schedule.scheduleViewMapper.reason.6");
            case "SCHEDULE_HIRE_UNAVAILABLE" -> Messages.text("error.schedule.scheduleViewMapper.reason.7");
            case "SCHEDULE_RESOURCE_UNAVAILABLE" -> Messages.text("error.schedule.scheduleViewMapper.reason.8");
            case "SCHEDULE_PERMISSION_DENIED" -> Messages.text("error.schedule.scheduleViewMapper.reason.9");
            case "SCHEDULE_CANCELLED" -> Messages.text("error.schedule.scheduleViewMapper.reason.10");
            case "SCHEDULE_NOTIFICATION_EXPIRED" -> Messages.text("error.schedule.scheduleViewMapper.reason.11");
            case "SCHEDULE_NOTIFICATION_PARTIAL" -> Messages.text("error.schedule.scheduleViewMapper.reason.12");
            case "SCHEDULE_NOTIFICATION_UNKNOWN" -> Messages.text("error.schedule.scheduleViewMapper.reason.13");
            case "SCHEDULE_NOTIFICATION_FAILED" -> Messages.text("error.schedule.scheduleViewMapper.reason.14");
            case "SCHEDULE_NOTIFICATION_BLOCKED" -> Messages.text("error.schedule.scheduleViewMapper.reason.15");
            case "SCHEDULE_PREPARATION_FAILED" -> Messages.text("error.schedule.scheduleViewMapper.reason.16");
            case "SCHEDULE_NOTIFICATIONS_DISABLED" -> Messages.text("error.schedule.scheduleViewMapper.reason.17");
            default -> Messages.text("error.schedule.scheduleViewMapper.reason.18");
        };
    }

    private String text(Instant value) {
        return value == null ? null : value.toString();
    }
}
