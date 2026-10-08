package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.service.execution.ExecutionConfigurationService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 计划始终属于本人；管理员权限也不开放其他成员的固定输入和执行内容。
 */
@Service
public class SchedulePolicy {
    private final EnterpriseAuthorizationService authorization;
    private final ScheduleMapper schedules;
    private final AgentHireMapper hires;
    private final ExecutionConfigurationService configurations;

    public SchedulePolicy(EnterpriseAuthorizationService authorization, ScheduleMapper schedules, AgentHireMapper hires,
                          ExecutionConfigurationService configurations) {
        this.authorization = authorization;
        this.schedules = schedules;
        this.hires = hires;
        this.configurations = configurations;
    }

    public void view(AuthContext actor) {
        authorization.require(actor, "schedule.view");
    }

    public void manage(AuthContext actor) {
        authorization.lockAndRequire(actor, "schedule.manage");
    }

    public void run(AuthContext actor) {
        authorization.require(actor, "agent.run");
    }

    public ScheduleRecord require(AuthContext actor, String id, boolean lock, boolean includeDeleted) {
        return schedules.find(actor.enterpriseId(), actor.userId(), id, lock, includeDeleted)
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public void revision(ScheduleRecord value, long expected) {
        if (value.revision() != expected) {
            throw ApiException.versionConflict(value.revision());
        }
    }

    public ExecutionConfigurationService.Selection select(AuthContext actor, String hireId, String version) {
        var hire = hires.find(actor.enterpriseId(), actor.userId(), hireId, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (!hire.status().equals("active")) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_UNAVAILABLE", "请先恢复该员工的雇佣，再创建或启用计划。");
        }
        var selected = configurations.normal(actor, hire.agentId(), version);
        if (!hire.id().equals(selected.hireId())) {
            throw ResourceAuthorizationService.unavailable();
        }
        return selected;
    }
}
