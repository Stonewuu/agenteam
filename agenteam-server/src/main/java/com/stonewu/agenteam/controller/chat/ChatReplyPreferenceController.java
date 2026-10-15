package com.stonewu.agenteam.controller.chat;

import com.stonewu.agenteam.model.chat.request.ChatReplyPreferenceRequest;
import com.stonewu.agenteam.model.chat.response.ChatReplyPreferenceView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.chat.ChatReplyPreferenceApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/chat/reply-preferences")
public class ChatReplyPreferenceController {
    private final ChatReplyPreferenceApiService service;
    private final ApiResponses responses;

    public ChatReplyPreferenceController(ChatReplyPreferenceApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<ChatReplyPreferenceView>> list(@PathVariable String enterpriseId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(service.list(enterpriseId, cursor, limit, request), request);
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ChatReplyPreferenceView> get(@PathVariable String enterpriseId, @PathVariable String conversationId, HttpServletRequest request) {
        return responses.success(service.get(enterpriseId, conversationId, request), request);
    }

    @PostMapping("/{conversationId}")
    public ResponseEntity<ApiResponse<Object>> save(@PathVariable String enterpriseId, @PathVariable String conversationId,
            @RequestBody ChatReplyPreferenceRequest input, HttpServletRequest request) {
        return responses.operation(service.save(enterpriseId, conversationId, input, request), request);
    }
}
