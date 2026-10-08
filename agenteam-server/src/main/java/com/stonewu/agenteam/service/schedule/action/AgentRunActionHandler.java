package com.stonewu.agenteam.service.schedule.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.entity.AgentScheduleSnapshot;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionDefinition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.request.AgentScheduleActionRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.notification.ChannelPayloadCodec;
import com.stonewu.agenteam.service.resource.ResourceInput;
import com.stonewu.agenteam.service.schedule.ScheduleActionHandler;
import com.stonewu.agenteam.service.schedule.SchedulePolicy;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Map;

/** 原智能体计划的参数、版本、权限、额度和模型重试保留在独立操作处理器中。 */
@Component
public class AgentRunActionHandler implements ScheduleActionHandler {
    private final SchedulePolicy policy;
    private final RunSubmissionService submission;
    private final ObjectProvider<RunLifecycleService> lifecycle;
    private final AgentHireMapper hires;
    private final RunMapper runs;
    private final ChannelPayloadCodec payloads;
    private final ObjectMapper json;

    public AgentRunActionHandler(SchedulePolicy policy, RunSubmissionService submission, ObjectProvider<RunLifecycleService> lifecycle,
                                  RunMapper runs, ChannelPayloadCodec payloads, ObjectMapper json, AgentHireMapper hires) {
        this.policy = policy;
        this.submission = submission;
        this.lifecycle = lifecycle;
        this.runs = runs;
        this.payloads = payloads;
        this.json = json;
        this.hires = hires;
    }

    @Override
    public String type() {
        return "agent.run";
    }

    @Override
    public String name() {
        return "执行智能体任务";
    }

    @Override
    public boolean usesAgent() {
        return true;
    }

    @Override
    public Map<String, Object> configurationSchema() {
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("hireId", "inputText"), "properties", Map.of(
            "hireId", Map.of("type", "string", "minLength", 1, "maxLength", 100),
            "agentVersionId", Map.of("type", List.of("string", "null"), "maxLength", 100),
            "inputText", Map.of("type", "string", "minLength", 1, "maxLength", 20000)));
    }

    @Override
    public boolean available(AuthContext actor) {
        return actor.permissions().contains("schedule.manage") && actor.permissions().contains("agent.run");
    }

    @Override
    public ScheduleActionDefinition prepare(AuthContext actor, ScheduleWriteRequest input, ScheduleRecord previous) {
        var action = input.action() == null ? new AgentScheduleActionRequest(input.hireId(), input.agentVersionId(), input.inputText())
            : InputValidation.read(json.valueToTree(input.action().config()), AgentScheduleActionRequest.class, "action.config");
        String prefix = input.action() == null ? "" : "action.config.";
        String text = ResourceInput.text(action.inputText(), prefix + "inputText", 20000, true);
        String hire = ResourceInput.text(action.hireId(), prefix + "hireId", 100, true);
        if (input.maxRetries() < 0 || input.maxRetries() > 2) {
            throw ApiException.invalidField("maxRetries", "智能体自动重试次数需要在零至两次之间。");
        }
        String version = action.agentVersionId();
        if (version == null && previous != null && type().equals(previous.actionType()) && Objects.equals(previous.hireId(), hire)) {
            version = previous.agentVersionId();
        }
        var selected = policy.select(actor, hire, version);
        return new ScheduleActionDefinition(type(), 1, "{}", selected.hireId(), selected.versionId(), text, input.maxRetries(), List.of());
    }

    @Override
    public void validateCurrent(AuthContext actor, ScheduleRecord schedule) {
        policy.run(actor);
        if (hires.find(actor.enterpriseId(), actor.userId(), schedule.hireId(), false).filter(value -> "active".equals(value.status())).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_HIRE_UNAVAILABLE", "员工雇佣已暂停或解除，计划暂时不能执行。");
        }
        policy.select(actor, schedule.hireId(), schedule.agentVersionId());
    }

    @Override
    public JsonNode snapshot(ScheduleRecord schedule) {
        return json.valueToTree(new AgentScheduleSnapshot(schedule.hireId(), schedule.agentVersionId(), schedule.inputText(), schedule.maxRetries()));
    }

    @Override
    public Map<String, Object> publicConfiguration(ScheduleRecord schedule) {
        return Map.of("hireId", schedule.hireId(), "agentVersionId", schedule.agentVersionId(), "inputText", schedule.inputText());
    }

    @Override
    public ScheduleActionSubmission submit(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        var snapshot = json.convertValue(payloads.decode(occurrence.getActionSnapshotJson()), AgentScheduleSnapshot.class);
        policy.run(actor);
        var selected = policy.select(actor, snapshot.hireId(), snapshot.agentVersionId());
        var result = json.createObjectNode().put("prepared", true);
        if (runs.activeCount(actor.enterpriseId(), null) >= 20 || runs.activeCount(actor.enterpriseId(), actor.userId()) >= 3) {
            return new ScheduleActionSubmission("blocked", "CONCURRENCY_LIMIT", null, null, result);
        }
        var accepted = submission.scheduled(actor, selected, snapshot.inputText(), occurrence.getScheduleId(), occurrence.getId(),
            "manual".equals(occurrence.getTriggerKind()), snapshot.maxRetries());
        return accepted.map(run -> new ScheduleActionSubmission("queued", null, null, run, result))
            .orElseGet(() -> new ScheduleActionSubmission("blocked", "QUOTA_EXCEEDED", null, null, result));
    }

    @Override
    public void cancel(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        if (occurrence.getRunId() != null) {
            // 生命周期服务也会触发计划权限复查，只在真正停止时取得，避免组件相互初始化。
            lifecycle.getObject().cancel(actor, occurrence.getRunId());
        }
    }

    @Override
    public String denialReason(ResponseStatusException failure) {
        if (failure instanceof ApiException error && "SCHEDULE_HIRE_UNAVAILABLE".equals(error.code())) {
            return error.code();
        }
        return ScheduleActionHandler.super.denialReason(failure);
    }
}
