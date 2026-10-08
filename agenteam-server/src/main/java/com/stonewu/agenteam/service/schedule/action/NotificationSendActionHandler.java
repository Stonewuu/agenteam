package com.stonewu.agenteam.service.schedule.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.notification.entity.ScheduledChannelNotification;
import com.stonewu.agenteam.model.schedule.entity.NotificationScheduleRecipient;
import com.stonewu.agenteam.model.schedule.entity.NotificationScheduleSnapshot;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionDefinition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.request.NotificationScheduleActionRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.schedule.response.ScheduleRecipientResult;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.notification.ChannelDeliveryCancellation;
import com.stonewu.agenteam.service.notification.ChannelNotificationRouting;
import com.stonewu.agenteam.service.notification.ChannelPayloadCodec;
import com.stonewu.agenteam.service.notification.NotificationWriteService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import com.stonewu.agenteam.service.schedule.ScheduleActionHandler;
import com.stonewu.agenteam.service.schedule.ScheduleNotificationTargetService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.List;

/** 定时通知始终保存站内内容，外部渠道排入独立工作；不启动智能体或占用模型额度。 */
@Component
public class NotificationSendActionHandler implements ScheduleActionHandler {
    private final ScheduleNotificationTargetService targets;
    private final NotificationWriteService writer;
    private final ChannelNotificationRouting routing;
    private final ChannelDeliveryCancellation cancellation;
    private final EnterpriseAuthorizationService authorization;
    private final ChannelPayloadCodec payloads;
    private final ObjectMapper json;
    private final Clock clock;
    private final NotificationScheduleResultService results;
    private final NotificationScheduleDetailService details;
    private final boolean enabled;

    public NotificationSendActionHandler(ScheduleNotificationTargetService targets, NotificationWriteService writer,
                                          ChannelNotificationRouting routing, ChannelDeliveryCancellation cancellation,
                                          EnterpriseAuthorizationService authorization, ChannelPayloadCodec payloads, ObjectMapper json, Clock clock,
                                          NotificationScheduleResultService results, NotificationScheduleDetailService details,
                                          @Value("${agenteam.schedule.notification-actions-enabled:true}") boolean enabled) {
        this.targets = targets;
        this.writer = writer;
        this.routing = routing;
        this.cancellation = cancellation;
        this.authorization = authorization;
        this.payloads = payloads;
        this.json = json;
        this.clock = clock;
        this.results = results;
        this.details = details;
        this.enabled = enabled;
    }

    @Override
    public String type() {
        return "notification.send";
    }

    @Override
    public String name() {
        return "发送通知";
    }

    @Override
    public boolean usesAgent() {
        return false;
    }

    @Override
    public Map<String, Object> configurationSchema() {
        var identity = Map.of("type", "string", "minLength", 1, "maxLength", 100);
        var recipient = Map.of("type", "object", "additionalProperties", false, "required", List.of("userId", "connectionIds"), "properties", Map.of(
            "userId", identity, "connectionIds", Map.of("type", "array", "maxItems", 10, "uniqueItems", true, "items", identity)));
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("title", "body", "recipients"), "properties", Map.of(
            "title", Map.of("type", "string", "minLength", 1, "maxLength", 100), "body", Map.of("type", "string", "maxLength", 500),
            "recipients", Map.of("type", "array", "minItems", 1, "maxItems", 50, "items", recipient)));
    }

    @Override
    public boolean available(AuthContext actor) {
        return enabled && actor.permissions().contains("schedule.manage");
    }

    @Override
    public ScheduleActionDefinition prepare(AuthContext actor, ScheduleWriteRequest input, ScheduleRecord previous) {
        requireEnabled();
        authorization.require(actor, "schedule.manage");
        var request = InputValidation.read(json.valueToTree(input.action().config()), NotificationScheduleActionRequest.class, "action.config");
        if (input.maxRetries() != 0) {
            throw ApiException.invalidField("maxRetries", "通知操作不使用智能体重试次数，请填写零。");
        }
        String title = ResourceInput.text(request.title(), "action.config.title", 100, true);
        String body = ResourceInput.text(request.body(), "action.config.body", 500, false);
        var recipients = targets.validate(actor, request.recipients());
        return new ScheduleActionDefinition(type(), 1, payloads.encode(json.createObjectNode().put("title", title).put("body", body)),
            null, null, null, 0, recipients);
    }

    @Override
    public void validateCurrent(AuthContext actor, ScheduleRecord schedule) {
        requireEnabled();
        authorization.require(actor, "schedule.manage");
        var recipients = targets.list(actor.enterpriseId(), schedule.id());
        if (recipients.isEmpty() || recipients.size() > 50) {
            throw ApiException.invalidField("action.config.recipients", "此通知计划没有有效的接收设置，请重新编辑计划。");
        }
        targets.requireExecutionScope(actor, recipients.stream().map(NotificationScheduleRecipient::userId).toList());
        content(schedule);
    }

    @Override
    public JsonNode snapshot(ScheduleRecord schedule) {
        var content = content(schedule);
        var recipients = targets.list(schedule.enterpriseId(), schedule.id());
        return json.valueToTree(new NotificationScheduleSnapshot(content.title(), content.body(), targets.capture(schedule.enterpriseId(), recipients)));
    }

    @Override
    public Map<String, Object> publicConfiguration(ScheduleRecord schedule) {
        var content = content(schedule);
        return Map.of("title", content.title(), "body", content.body());
    }

    @Override
    public ScheduleActionSubmission submit(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        requireEnabled();
        authorization.require(actor, "schedule.manage");
        var snapshot = json.convertValue(payloads.decode(occurrence.getActionSnapshotJson()), NotificationScheduleSnapshot.class);
        targets.requireExecutionScope(actor, snapshot.recipients().stream().map(NotificationScheduleSnapshot.Recipient::userId).toList());
        var result = json.createObjectNode().put("prepared", true);
        var blocked = result.putArray("blockedRecipients");
        var expires = occurrence.getScheduledFor().plusSeconds(3600);
        if (!expires.isAfter(clock.instant())) {
            for (var recipient : snapshot.recipients()) {
                blocked.addObject().put("userId", recipient.userId()).put("reasonCode", "NOTIFICATION_EXPIRED").put("reason", "本次通知已超过发送有效期。");
            }
            result.put("inAppCount", 0);
            return new ScheduleActionSubmission("blocked", "SCHEDULE_NOTIFICATION_EXPIRED", "本次通知已超过发送有效期。", null, result);
        }
        int inAppCount = 0;
        var channels = new ArrayList<ScheduledChannelNotification>();
        for (var recipient : snapshot.recipients().stream().sorted(Comparator.comparing(NotificationScheduleSnapshot.Recipient::userId)).toList()) {
            var written = writer.write(actor.enterpriseId(), recipient.userId(), new Notice("schedule-notification:" + occurrence.getId(), "schedule",
                snapshot.title(), snapshot.body(), null, null), occurrence.getId(), actor.userId());
            if (written.isEmpty()) {
                blocked.addObject().put("userId", recipient.userId()).put("reasonCode", "RECIPIENT_UNAVAILABLE")
                    .put("reason", "此成员已不在企业中或账号已停用。");
                continue;
            }
            inAppCount++;
            for (var target : recipient.channels()) {
                channels.add(new ScheduledChannelNotification(written.get().notification(), target, expires));
            }
        }
        routing.scheduled(channels);
        result.put("inAppCount", inAppCount);
        return new ScheduleActionSubmission("running", null, null, null, result);
    }

    @Override
    public void cancel(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        cancellation.occurrence(actor.enterpriseId(), occurrence.getId());
    }

    @Override
    public Optional<ScheduleActionSubmission> reconcile(ScheduledOccurrenceRow occurrence) {
        return Optional.of(results.summarize(occurrence));
    }

    @Override
    public List<ScheduleRecipientResult> recipientResults(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        return details.details(actor, occurrence);
    }

    private Content content(ScheduleRecord schedule) {
        var value = payloads.decode(schedule.actionConfigJson());
        InputValidation.fields(value, "action.config", "title", "body");
        return new Content(ResourceInput.text(value.path("title").textValue(), "action.config.title", 100, true),
            ResourceInput.text(value.path("body").textValue(), "action.config.body", 500, false));
    }

    @Override
    public String denialReason(ResponseStatusException failure) {
        return failure instanceof ApiException error && "SCHEDULE_NOTIFICATIONS_DISABLED".equals(error.code())
            ? error.code() : ScheduleActionHandler.super.denialReason(failure);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_NOTIFICATIONS_DISABLED", "通知计划已暂时关闭，请稍后再试。");
        }
    }

    private record Content(String title, String body) {
    }
}
