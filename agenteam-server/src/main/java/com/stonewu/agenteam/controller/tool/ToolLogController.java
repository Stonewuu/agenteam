package com.stonewu.agenteam.controller.tool;

import com.stonewu.agenteam.model.tool.request.ToolLogQuery;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.tool.ToolLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 查询仅返回当前权限范围内的调用，不能通过编号直接读取密文。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/tool-calls")
public class ToolLogController {

    private final ToolLogService logs;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public ToolLogController(ToolLogService logs, AuthContextService identity, ApiResponses responses) {
        this.logs = logs;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping
    public Object list(@PathVariable String enterpriseId, @RequestParam(required = false) String query,
                       @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                       @RequestParam(required = false) String actorUserId,
                       @RequestParam(required = false) String status, @RequestParam(required = false) String source,
                       HttpServletRequest request) {
        return responses.success(logs.list(identity.requireEnterprise(request.getSession(false), enterpriseId),
            new ToolLogQuery(from, to, actorUserId, status, source, query), cursor, limit), request);
    }

    @GetMapping("/{callId}")
    public Object details(@PathVariable String enterpriseId, @PathVariable String callId, HttpServletRequest request) {
        return responses.success(
            logs.details(identity.requireEnterprise(request.getSession(false), enterpriseId), callId), request);
    }
}
