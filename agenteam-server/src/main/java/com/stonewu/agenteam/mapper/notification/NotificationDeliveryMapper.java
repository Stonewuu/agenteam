package com.stonewu.agenteam.mapper.notification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 业务事务只排入提醒任务；实际通知编号在独立事务中按成员分配。
 */
@Repository
public class NotificationDeliveryMapper {
    public record Notice(String eventKey, String category, String title, String body, String targetType,
                         String targetId) {
    }

    public record Candidate(String id, String enterprise, String user) {
    }

    private final NotificationDeliverySqlMapper statements;
    private final ResourceJson json;

    private final UserPreferenceMapper userPreferenceMapper;

    public NotificationDeliveryMapper(NotificationDeliverySqlMapper statements, ResourceJson json,
                                      UserPreferenceMapper userPreferenceMapper) {
        this.userPreferenceMapper = userPreferenceMapper;
        this.statements = statements;
        this.json = json;
    }

    public void enqueue(String enterprise, String user, Notice notice, Instant now) {
        enqueue(enterprise, user, notice, now, now);
    }

    public void enqueue(String enterprise, String user, Notice notice, Instant now, Instant availableAt) {
        var payload = (ObjectNode) json.tree(notice);
        payload.put("channelRoutingVersion", 1);
        statements.enqueueBackgroundJob(UUID.randomUUID().toString(), enterprise, user,
            "notification:" + enterprise + ":" + user + ":" + notice.eventKey(), json.write(payload),
            timestamp(now), timestamp(availableAt));
    }

    /** 旧版排队事件没有渠道规则版本，只沿用原站内通知行为。 */
    public boolean allowsExternalChannels(BackgroundJobRow job) {
        var version = json.read(job.getPayloadJson()).path("channelRoutingVersion");
        return version.isIntegralNumber() && version.intValue() == 1;
    }

    public List<Candidate> candidates(Instant now) {
        return statements.candidatesBackgroundJob(timestamp(now)).stream()
            .map(row -> new Candidate(row.getId(), row.getEnterpriseId(), row.getOwnerUserId())).toList();
    }

    public Optional<BackgroundJobRow> lockPending(Candidate candidate) {
        return statements.deliverBackgroundJob(candidate.id(), candidate.enterprise(), candidate.user()).stream().findFirst();
    }

    public Notice notice(BackgroundJobRow job) {
        var payload = json.read(job.getPayloadJson());
        return new Notice(payload.path("eventKey").asText(), payload.path("category").asText(), payload.path("title").asText(),
            payload.path("body").asText(), payload.path("targetType").asText(null), payload.path("targetId").asText(null));
    }

    public void finish(Candidate candidate, String status, Instant now) {
        if (statements.finishNotificationJob(status, timestamp(now), candidate.id(), candidate.enterprise(), candidate.user()) != 1) {
            throw new IllegalStateException("通知工作状态已变化，当前写入未提交");
        }
    }

    public boolean completionEnabled(String user) {
        return userPreferenceMapper.completionEnabledUserPreference(user).stream().findFirst().orElse(true);
    }
}
