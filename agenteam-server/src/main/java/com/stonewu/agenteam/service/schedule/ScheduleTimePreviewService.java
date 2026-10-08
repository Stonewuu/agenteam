package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.schedule.ScheduleRuleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleTimesMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.request.ScheduleRuleInput;
import com.stonewu.agenteam.model.schedule.response.ScheduleTimesView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 时间预览检查本人当前权限，读取时间而不创建执行或次数记录。
 */
@Service
public class ScheduleTimePreviewService {
    private final AuthContextService identity;
    private final EnterpriseAuthorizationService authorization;
    private final ScheduleRuleMapper rules;
    private final ScheduleTimeCalculator calculator;
    private final ScheduleTimesMapper views;
    private final Clock clock;

    public ScheduleTimePreviewService(AuthContextService identity, EnterpriseAuthorizationService authorization,
                                      ScheduleRuleMapper rules, ScheduleTimeCalculator calculator,
                                      ScheduleTimesMapper views, Clock clock) {
        this.identity = identity;
        this.authorization = authorization;
        this.rules = rules;
        this.calculator = calculator;
        this.views = views;
        this.clock = clock;
    }

    public ScheduleTimesView preview(String enterprise, ScheduleRuleInput input, HttpServletRequest request) {
        return preview(identity.requireEnterprise(request.getSession(false), enterprise), input);
    }

    public ScheduleTimesView preview(AuthContext actor, ScheduleRuleInput input) {
        authorization.require(actor, "schedule.manage");
        InputValidation.validate(input);
        var rule = rules.from(input);
        return views.view(rule, calculator.next(rule, clock.instant(), 5));
    }
}
