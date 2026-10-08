package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.model.execution.request.PreviewInput;
import com.stonewu.agenteam.service.execution.AgentPreviewApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/agents/{resourceId}/preview")
public class AgentPreviewController {

    private final AgentPreviewApiService previews;

    private final ApiResponses responses;

    public AgentPreviewController(AgentPreviewApiService previews, ApiResponses responses) {
        this.previews = previews;
        this.responses = responses;
    }

    @PostMapping
    public ResponseEntity<?> preview(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                     @RequestBody PreviewInput input, HttpServletRequest request) {
        return responses.operation(previews.preview(enterpriseId, resourceId, input, request), request);
    }
}
