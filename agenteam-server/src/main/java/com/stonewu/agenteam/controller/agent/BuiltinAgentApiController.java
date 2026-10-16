package com.stonewu.agenteam.controller.agent;

import com.stonewu.agenteam.model.agent.request.BuiltinAgentStartInput;
import com.stonewu.agenteam.service.agent.BuiltinAgentApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 内置助手的对话入口，后续消息继续使用普通对话接口。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/agent-templates/{code}")
public class BuiltinAgentApiController {
    private final BuiltinAgentApiService agents;
    private final ApiResponses responses;

    public BuiltinAgentApiController(BuiltinAgentApiService agents, ApiResponses responses) {
        this.agents = agents;
        this.responses = responses;
    }

    @PostMapping("/conversations")
    public ResponseEntity<?> start(@PathVariable String enterpriseId, @PathVariable String code,
                                    @RequestBody BuiltinAgentStartInput body, HttpServletRequest request) {
        return responses.operation(agents.start(enterpriseId, code, body, request), request);
    }
}
