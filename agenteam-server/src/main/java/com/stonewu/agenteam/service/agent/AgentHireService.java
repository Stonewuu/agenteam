package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentHireApplicationMapper;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireApplication;
import com.stonewu.agenteam.model.agent.request.HireAgentRequest;
import com.stonewu.agenteam.model.agent.request.HireDecisionRequest;
import com.stonewu.agenteam.model.agent.response.AgentHireApplicationView;
import com.stonewu.agenteam.model.agent.response.AgentHireResult;
import com.stonewu.agenteam.model.agent.response.AgentHireResult.ApprovalRequired;
import com.stonewu.agenteam.model.agent.response.AgentHireResult.Hired;
import com.stonewu.agenteam.model.agent.response.AgentHireView;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 使用关系与审批决定在同一企业事务中更新，重新检查当前授权和截止时间。
 */
@Service
public class AgentHireService {
    private final AgentHireMapper hires;
    private final AgentHireApplicationMapper applications;
    private final EmployeeAccessService employees;
    private final EnterpriseAuthorizationService authorization;
    private final ResourcePolicy resources;
    private final AuthMapper users;
    private final AuditEventService audit;
    private final Clock clock;
    private final RunLifecycleService executions;
    private final HireDecisionNotificationService notifications;

    public AgentHireService(AgentHireMapper hires, AgentHireApplicationMapper applications,
                            EmployeeAccessService employees,
                            EnterpriseAuthorizationService authorization, ResourcePolicy resources, AuthMapper users,
                            AuditEventService audit, Clock clock, RunLifecycleService executions,
                            HireDecisionNotificationService notifications) {
        this.hires = hires;
        this.applications = applications;
        this.employees = employees;
        this.authorization = authorization;
        this.resources = resources;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.executions = executions;
        this.notifications = notifications;
    }

    public void authorizeHire(AuthContext actor) {
        authorization.lockAndRequire(actor, "agent.hire");
    }

    public void authorizeDecision(AuthContext actor, String id) {
        authorization.lockAndRequire(actor, "agent.hire_approve");
        var application = applications.find(actor.enterpriseId(), id, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        resources.authorize(actor, application.agentId(), "hire_approve", true, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AgentHireResult hire(AuthContext actor, HireAgentRequest value) {
        authorizeHire(actor);
        var existing = hires.forAgent(actor.enterpriseId(), actor.userId(), value.agentId(), true).orElse(null);
        if (existing != null && existing.status().equals("active")) {
            return new Hired(hires.view(existing));
        }
        if (existing != null && existing.status().equals("paused")) {
            return new Hired(status(actor, existing.id(), "active", existing.revision()));
        }
        var source = employees.requireAvailable(actor, value.agentId(), "agent.hire", true);
        String agentId = source.resource().id();
        var now = clock.instant();
        applications.expireFor(actor.enterpriseId(), actor.userId(), agentId, now);
        var pending = applications.pending(actor.enterpriseId(), actor.userId(), agentId).orElse(null);
        if (source.listing().hirePolicy().equals("approval")) {
            if (pending != null) {
                return new ApprovalRequired(applicationView(actor, pending));
            }
            String note = ResourceInput.text(value.note() == null ? "" : value.note(), "note", 500, false);
            var created = applications.create(actor.enterpriseId(), actor.userId(), agentId, note, now,
                now.plus(Duration.ofDays(7)));
            audit.record(actor.enterpriseId(), actor.user(), "agent.hire.request", "agent_hire_request", created.id(),
                "申请雇佣数字员工", Map.of("agentId", agentId));
            return new ApprovalRequired(applicationView(actor, created));
        }
        var hire = hires.establish(actor.enterpriseId(), actor.userId(), agentId, now);
        if (pending != null) {
            applications.finish(pending, "approved", actor.userId(), "当前设置允许自行雇佣，已建立使用关系。", now);
            notifications.decided(pending, true);
        }
        audit.record(actor.enterpriseId(), actor.user(), "agent.hire.create", "agent_hire", hire.id(), "雇佣数字员工",
            Map.of("agentId", agentId));
        return new Hired(hires.view(hire));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AgentHireView status(AuthContext actor, String id, String status, long revision) {
        authorizeHire(actor);
        var hire = hires.find(actor.enterpriseId(), actor.userId(), id, true)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (hire.revision() != revision) {
            throw ApiException.versionConflict(hire.revision());
        }
        if (status == null || !Set.of("active", "paused", "terminated").contains(status)) {
            throw ApiException.invalidField("status", "请选择恢复、暂停或解除雇佣。");
        }
        if (hire.status().equals(status)) {
            return hires.view(hire);
        }
        if (hire.status().equals("terminated")) {
            throw new ApiException(HttpStatus.CONFLICT, "HIRE_TERMINATED", "雇佣已经解除，请重新雇佣该员工。");
        }
        if (status.equals("active")) {
            employees.requireAvailable(actor, hire.agentId(), "agent.hire", false);
        }
        hires.changeStatus(hire, status, clock.instant());
        if (!status.equals("active")) {
            executions.stopForResource(actor.enterpriseId(), hire.agentId(), actor.userId());
        }
        audit.record(actor.enterpriseId(), actor.user(), "agent.hire.status", "agent_hire", id, "修改本人雇佣状态",
            Map.of("before", hire.status(), "after", status, "agentId", hire.agentId()));
        return hires.view(hires.find(actor.enterpriseId(), actor.userId(), id, false).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AgentHireApplicationView decide(AuthContext actor, String id, HireDecisionRequest value, long revision) {
        authorizeDecision(actor, id);
        var application = applications.find(actor.enterpriseId(), id, true)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        pending(application, revision);
        if (value == null || value.decision() == null || !Set.of("approve", "reject").contains(value.decision())) {
            throw ApiException.invalidField("decision", "请选择批准或拒绝。");
        }
        boolean approve = value.decision().equals("approve");
        String reason = ResourceInput.text(value.reason() == null ? "" : value.reason(), "reason", 500, !approve);
        if (approve) {
            var user = users.findById(application.userId()).filter(candidate -> candidate.status().equals("active"))
                .orElseThrow(ResourceAuthorizationService::unavailable);
            var applicant = new AuthContext(user, actor.enterpriseId(), Set.of());
            employees.requireAvailable(applicant, application.agentId(), "agent.hire", true);
            hires.establish(actor.enterpriseId(), user.id(), application.agentId(), clock.instant());
        }
        var now = clock.instant();
        applications.finish(application, approve ? "approved" : "rejected", actor.userId(),
            reason.isEmpty() ? null : reason, now);
        notifications.decided(application, approve);
        audit.record(actor.enterpriseId(), actor.user(), "agent.hire.decision", "agent_hire_request", id,
            approve ? "批准雇佣申请" : "拒绝雇佣申请",
            Map.of("agentId", application.agentId(), "applicantId", application.userId(), "decision",
                value.decision()));
        return applications.view(applications.find(actor.enterpriseId(), id, false).orElseThrow(), now, false, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AgentHireApplicationView withdraw(AuthContext actor, String id, long revision) {
        authorizeHire(actor);
        var application = applications.find(actor.enterpriseId(), id, true)
            .filter(value -> value.userId().equals(actor.userId()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        pending(application, revision);
        var now = clock.instant();
        applications.finish(application, "withdrawn", actor.userId(), null, now);
        audit.record(actor.enterpriseId(), actor.user(), "agent.hire.withdraw", "agent_hire_request", id,
            "撤回本人雇佣申请", Map.of("agentId", application.agentId()));
        return applications.view(applications.find(actor.enterpriseId(), id, false).orElseThrow(), now, false, false);
    }

    private void pending(AgentHireApplication application, long revision) {
        if (application.revision() != revision) {
            throw ApiException.versionConflict(application.revision());
        }
        if (application.status().equals("expired")) {
            throw new ApiException(HttpStatus.CONFLICT, "HIRE_APPLICATION_EXPIRED", "雇佣申请已过期，请重新申请。");
        }
        if (!application.status().equals("pending")) {
            throw new ApiException(HttpStatus.CONFLICT, "HIRE_APPLICATION_FINISHED",
                "这条申请已经处理，请重新加载列表。");
        }
        if (!application.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "HIRE_APPLICATION_EXPIRED", "雇佣申请已过期，请重新申请。");
        }
    }

    public int expireApplications() {
        return applications.expire(clock.instant());
    }

    private AgentHireApplicationView applicationView(AuthContext actor, AgentHireApplication value) {
        boolean decide = false;
        try {
            resources.authorize(actor, value.agentId(), "hire_approve", false, false);
            decide = true;
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode().value() != 403 && denied.getStatusCode().value() != 404) {
                throw denied;
            }
        }
        return applications.view(value, clock.instant(), true, decide);
    }
}
