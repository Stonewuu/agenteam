package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentHireApplicationMapper;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/** 群聊首次实际使用时建立无需审批的雇佣，不自动恢复用户已暂停或解除的关系。 */
@Service
public class AgentChatHireService {
    private final AgentHireMapper hires;
    private final AgentHireApplicationMapper applications;
    private final EmployeeAccessService employees;
    private final EnterpriseAuthorizationService authorization;
    private final AuditEventService audit;
    private final HireDecisionNotificationService notifications;
    private final Clock clock;

    public AgentChatHireService(AgentHireMapper hires, AgentHireApplicationMapper applications, EmployeeAccessService employees,
                               EnterpriseAuthorizationService authorization, AuditEventService audit, Clock clock,
                               HireDecisionNotificationService notifications) {
        this.hires = hires;
        this.applications = applications;
        this.employees = employees;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
        this.notifications = notifications;
    }

    public void requireUsable(AuthContext actor, String agent) {
        var existing = hires.forAgent(actor.enterpriseId(), actor.userId(), agent, false).orElse(null);
        if (existing != null) {
            switch (existing.status()) {
                case "active" -> {
                    return;
                }
                case "paused" -> throw new ApiException(HttpStatus.CONFLICT, "AGENT_CHAT_HIRE_PAUSED", "你已暂停使用这位员工，请在群策「数字员工 → 我的员工」恢复后再发送任务。");
                case "terminated" -> throw new ApiException(HttpStatus.CONFLICT, "AGENT_CHAT_HIRE_TERMINATED", "你已解除这位员工的雇佣，请在群策「数字员工」重新启用后再发送任务。");
                default -> throw new ApiException(HttpStatus.CONFLICT, "AGENT_CHAT_HIRE_UNAVAILABLE", "当前员工使用关系不可用，请在群策「数字员工」检查。");
            }
        }
        authorization.require(actor, "agent.hire");
        // 群里已配置的岗位不要求再次到员工广场发现，但仍检查本人资源、版本及依赖权限。
        var source = employees.requireAvailable(actor, agent, "agent.hire", false);
        if (source.listing().hirePolicy().equals("approval")) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_CHAT_APPROVAL_REQUIRED", "这位员工需要审批后才能使用，请在群策「数字员工」提交申请。");
        }
    }

    @Transactional
    public void ensure(AuthContext actor, String agent) {
        authorization.lockAndRequire(actor, "agent.run");
        requireUsable(actor, agent);
        if (hires.forAgent(actor.enterpriseId(), actor.userId(), agent, true).isPresent()) {
            return;
        }
        var now = clock.instant();
        var hire = hires.establish(actor.enterpriseId(), actor.userId(), agent, now);
        applications.expireFor(actor.enterpriseId(), actor.userId(), agent, now);
        applications.pending(actor.enterpriseId(), actor.userId(), agent).ifPresent(pending -> {
            applications.finish(pending, "approved", actor.userId(), "当前设置允许直接使用，首次机器人任务已建立使用关系。", now);
            notifications.decided(pending, true);
        });
        audit.record(actor.enterpriseId(), actor.user(), "agent.hire.chat", "agent_hire", hire.id(),
            "首次通过机器人使用数字员工", Map.of("agentId", agent));
    }
}
