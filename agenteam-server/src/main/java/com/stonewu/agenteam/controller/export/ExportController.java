package com.stonewu.agenteam.controller.export;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.tool.request.ToolLogQuery;
import com.stonewu.agenteam.service.export.ExportApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class ExportController {

    private final ExportApiService exports;

    private final ApiResponses responses;

    public ExportController(ExportApiService exports, ApiResponses responses) {
        this.exports = exports;
        this.responses = responses;
    }

    @PostMapping("/conversations/{conversationId}/export")
    public ResponseEntity<ApiResponse<Object>> conversation(@PathVariable String enterpriseId,
                                                            @PathVariable String conversationId,
                                                            HttpServletRequest request) {
        return responses.operation(exports.conversation(enterpriseId, conversationId, request), request);
    }

    @PostMapping("/tool-calls/export")
    public ResponseEntity<ApiResponse<Object>> tools(@PathVariable String enterpriseId, @RequestBody ToolLogQuery body,
                                                     HttpServletRequest request) {
        return responses.operation(exports.tools(enterpriseId, body, request), request);
    }
}
