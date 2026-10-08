package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * 自动触发也使用创建人当前资格，不能继承创建时的浏览器权限。
 */
@Service
public class ScheduleAccessService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleAccessService.class);

    public record Checked(AuthContext actor, String reason) {
    }

    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final PermissionMapper permissions;
    private final ScheduleActionRegistry actions;

    public ScheduleAccessService(AuthMapper users, EnterpriseMapper enterprises, PermissionMapper permissions,
                                 ScheduleActionRegistry actions) {
        this.users = users;
        this.enterprises = enterprises;
        this.permissions = permissions;
        this.actions = actions;
    }

    public Checked check(ScheduleRecord schedule) {
        var common = owner(schedule);
        if (common.reason() != null) {
            return common;
        }
        ScheduleActionHandler handler = null;
        try {
            handler = actions.require(schedule.actionType(), schedule.actionSchemaVersion());
            handler.validateCurrent(common.actor(), schedule);
            return common;
        } catch (ResponseStatusException failure) {
            log.warn("定时计划当前执行资格检查失败，计划编号={}", schedule.id(), failure);
            return denied(handler == null ? "SCHEDULE_RESOURCE_UNAVAILABLE" : handler.denialReason(failure));
        }
    }

    public AuthContext requireOwner(ScheduleRecord schedule) {
        var checked = owner(schedule);
        if (checked.reason() != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, checked.reason(), "创建人的账号、成员资格或定时任务权限已不可用。");
        }
        return checked.actor();
    }

    /** 已触发任务另由固定快照验证操作权限，不读取后来修改的计划配置。 */
    private Checked owner(ScheduleRecord schedule) {
        var user = users.findById(schedule.userId()).orElse(null);
        if (user == null || !user.status().equals("active") || !enterprises.activeMemberExists(schedule.enterpriseId(),
            schedule.userId())
            || enterprises.findById(schedule.enterpriseId()).filter(value -> value.status().equals("active"))
            .isEmpty()) {
            return denied("SCHEDULE_OWNER_UNAVAILABLE");
        }
        if (permissions.operationScope(schedule.userId(), schedule.enterpriseId(), "schedule.manage").isEmpty()) {
            return denied("SCHEDULE_PERMISSION_DENIED");
        }
        var actor = new AuthContext(user, schedule.enterpriseId(),
            Set.copyOf(permissions.listPermissionCodes(user.id(), schedule.enterpriseId())));
        return new Checked(actor, null);
    }

    private Checked denied(String reason) {
        return new Checked(null, reason);
    }
}
