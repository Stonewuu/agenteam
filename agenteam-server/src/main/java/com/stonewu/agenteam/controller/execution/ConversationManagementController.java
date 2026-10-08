package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.model.execution.request.ConversationUpdateInput;
import com.stonewu.agenteam.model.execution.request.MessageFeedbackInput;
import com.stonewu.agenteam.service.execution.ConversationManagementApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class ConversationManagementController {

    private final ConversationManagementApiService management;

    private final ApiResponses responses;

    public ConversationManagementController(ConversationManagementApiService management, ApiResponses responses) {
        this.management = management;
        this.responses = responses;
    }

    @PatchMapping("/conversations/{conversationId}")
    public ResponseEntity<?> update(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                    @RequestBody ConversationUpdateInput input, HttpServletRequest request) {
        return responses.operation(management.update(enterpriseId, conversationId, input, request), request);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<?> delete(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                    HttpServletRequest request) {
        return responses.operation(management.delete(enterpriseId, conversationId, request), request);
    }

    @PostMapping("/conversations/{conversationId}/restore")
    public ResponseEntity<?> restore(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                     HttpServletRequest request) {
        return responses.operation(management.restore(enterpriseId, conversationId, request), request);
    }

    @PutMapping("/messages/{messageId}/feedback")
    public ResponseEntity<?> feedback(@PathVariable String enterpriseId, @PathVariable String messageId,
                                      @RequestBody MessageFeedbackInput input, HttpServletRequest request) {
        return responses.operation(management.feedback(enterpriseId, messageId, input, request), request);
    }
}
