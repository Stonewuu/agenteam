package com.stonewu.agenteam.service.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionDefinition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.schedule.response.ScheduleRecipientResult;

import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import org.springframework.web.server.ResponseStatusException;

/**
 * 操作自行负责参数、授权和固定快照。提交与取消只修改数据库或排入持久工作，禁止在事务中调用外部网络。
 * 调度与工作领取不识别具体业务类型；新增操作实现此接口并注册为 Spring 组件即可接入。
 */
public interface ScheduleActionHandler {
    String type();

    String name();

    boolean usesAgent();

    Map<String, Object> configurationSchema();

    default Set<Integer> schemaVersions() {
        return Set.of(1);
    }

    boolean available(AuthContext actor);

    ScheduleActionDefinition prepare(AuthContext actor, ScheduleWriteRequest input, ScheduleRecord previous);

    void validateCurrent(AuthContext actor, ScheduleRecord schedule);

    JsonNode snapshot(ScheduleRecord schedule);

    Map<String, Object> publicConfiguration(ScheduleRecord schedule);

    ScheduleActionSubmission submit(AuthContext actor, ScheduledOccurrenceRow occurrence);

    void cancel(AuthContext actor, ScheduledOccurrenceRow occurrence);

    /** 已提交的操作可汇总后续持久工作的结果；智能体由其原生命周期更新发生状态。 */
    default Optional<ScheduleActionSubmission> reconcile(ScheduledOccurrenceRow occurrence) {
        return Optional.empty();
    }

    default List<ScheduleRecipientResult> recipientResults(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        return List.of();
    }

    default String denialReason(ResponseStatusException failure) {
        return failure.getStatusCode().value() == 403 ? "SCHEDULE_PERMISSION_DENIED" : "SCHEDULE_RESOURCE_UNAVAILABLE";
    }
}
