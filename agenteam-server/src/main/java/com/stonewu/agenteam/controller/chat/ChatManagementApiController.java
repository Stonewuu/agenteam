package com.stonewu.agenteam.controller.chat;

import com.stonewu.agenteam.model.chat.request.ChatBotWriteRequest;
import com.stonewu.agenteam.model.chat.request.ChatConversationUpdateRequest;
import com.stonewu.agenteam.model.chat.request.ChatCheckRequest;
import com.stonewu.agenteam.model.chat.request.ChatWebhookSettingsRequest;
import com.stonewu.agenteam.service.chat.ChatOutboundQueryService;
import com.stonewu.agenteam.service.chat.ChatAgentChoiceService;
import com.stonewu.agenteam.service.chat.ChatTestMemberService;
import com.stonewu.agenteam.model.chat.response.ChatBotView;
import com.stonewu.agenteam.model.chat.response.ChatConversationView;
import com.stonewu.agenteam.model.chat.response.ChatServiceStatus;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.chat.ChatManagementApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理请求只做协议适配，企业和系统权限由业务服务核验。 */
@RestController
@RequestMapping({"/api/v1/enterprises/{enterpriseId}/chat", "/api/v1/system/enterprises/{enterpriseId}/chat"})
public class ChatManagementApiController {
    private final ChatManagementApiService chats;
    private final ApiResponses responses;

    public ChatManagementApiController(ChatManagementApiService chats, ApiResponses responses) {
        this.chats = chats;
        this.responses = responses;
    }

    @GetMapping("/bots")
    public ApiResponse<PageResponse<ChatBotView>> bots(@PathVariable String enterpriseId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(chats.bots(enterpriseId, cursor, limit, request), request);
    }

    @GetMapping("/status")
    public ApiResponse<ChatServiceStatus> status(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(chats.status(enterpriseId, request), request);
    }

    @PostMapping("/bots")
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId, @RequestBody ChatBotWriteRequest input,
                                                     HttpServletRequest request) {
        return responses.operation(chats.create(enterpriseId, input, request), request);
    }

    @GetMapping("/agent-versions")
    public ApiResponse<PageResponse<ChatAgentChoiceService.Choice>> agents(@PathVariable String enterpriseId,
            @RequestParam(required = false) String agentId, @RequestParam(required = false) String versionId,
            @RequestParam(required = false) String query, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(chats.agents(enterpriseId, agentId, versionId, query, cursor, limit, request), request);
    }

    @PatchMapping("/bots/{id}")
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId, @PathVariable String id,
                                                     @RequestBody ChatBotWriteRequest input, HttpServletRequest request) {
        return responses.operation(chats.updateBot(enterpriseId, id, input, request), request);
    }

    @PostMapping("/bots/{id}/conversations/discover")
    public ResponseEntity<ApiResponse<Object>> discover(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        return responses.operation(chats.discover(enterpriseId, id, cursor, request), request);
    }

    @GetMapping("/conversations")
    public ApiResponse<PageResponse<ChatConversationView>> conversations(@PathVariable String enterpriseId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(chats.conversations(enterpriseId, cursor, limit, request), request);
    }

    @PostMapping("/bots/{id}/checks")
    public ResponseEntity<ApiResponse<Object>> check(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody ChatCheckRequest input, HttpServletRequest request) {
        return responses.operation(chats.check(enterpriseId, id, input, request), request);
    }

    @GetMapping("/bots/{id}/test-members")
    public ApiResponse<PageResponse<ChatTestMemberService.Choice>> testMembers(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestParam(required = false) String query, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(chats.testMembers(enterpriseId, id, query, cursor, limit, request), request);
    }

    @PostMapping("/bots/{id}/receive-probes")
    public ResponseEntity<ApiResponse<Object>> receiveProbe(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody ChatCheckRequest input, HttpServletRequest request) {
        return responses.operation(chats.probe(enterpriseId, id, input, true, request), request);
    }

    @PostMapping("/bots/{id}/send-probes")
    public ResponseEntity<ApiResponse<Object>> sendProbe(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody ChatCheckRequest input, HttpServletRequest request) {
        return responses.operation(chats.probe(enterpriseId, id, input, false, request), request);
    }

    @PostMapping("/bots/{id}/callback-settings")
    public ResponseEntity<ApiResponse<Object>> callbackSettings(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody ChatWebhookSettingsRequest input, HttpServletRequest request) {
        return responses.operation(chats.callbackSettings(enterpriseId, id, input, request), request);
    }

    @GetMapping("/outbounds")
    public ApiResponse<PageResponse<ChatOutboundQueryService.View>> outbounds(@PathVariable String enterpriseId,
            @RequestParam(required = false) String conversationId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(chats.outbounds(enterpriseId, conversationId, cursor, limit, request), request);
    }

    @PostMapping("/outbounds/{id}/retry")
    public ResponseEntity<ApiResponse<Object>> retry(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody ChatCheckRequest input, HttpServletRequest request) {
        return responses.operation(chats.retry(enterpriseId, id, input, request), request);
    }

    @PatchMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<Object>> updateConversation(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody ChatConversationUpdateRequest input, HttpServletRequest request) {
        return responses.operation(chats.updateConversation(enterpriseId, id, input, request), request);
    }
}
