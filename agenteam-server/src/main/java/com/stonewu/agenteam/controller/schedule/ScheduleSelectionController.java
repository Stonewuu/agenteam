package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.schedule.ScheduleSelectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 只返回当前用户可以使用的操作和通知目标。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class ScheduleSelectionController {
    private final AuthContextService identity;
    private final ScheduleSelectionService selections;
    private final ApiResponses responses;

    public ScheduleSelectionController(AuthContextService identity, ScheduleSelectionService selections, ApiResponses responses) {
        this.identity = identity;
        this.selections = selections;
        this.responses = responses;
    }

    @GetMapping("/schedule-actions")
    public Object actions(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(selections.actions(identity.requireEnterprise(request.getSession(false), enterpriseId)), request);
    }

    @GetMapping("/schedule-recipients")
    public Object recipients(@PathVariable String enterpriseId, @RequestParam(required = false) String query,
                             @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                             HttpServletRequest request) {
        return responses.success(selections.recipients(identity.requireEnterprise(request.getSession(false), enterpriseId), query, cursor, limit), request);
    }
}
