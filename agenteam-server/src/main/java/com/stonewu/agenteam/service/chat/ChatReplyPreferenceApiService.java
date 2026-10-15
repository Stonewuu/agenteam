package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.request.ChatReplyPreferenceRequest;
import com.stonewu.agenteam.model.chat.response.ChatReplyPreferenceView;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class ChatReplyPreferenceApiService {
    private final AuthContextService identity;
    private final ChatReplyPreferenceService preferences;
    private final IdempotentRequestService requests;
    private final ResourceJson json;

    public ChatReplyPreferenceApiService(AuthContextService identity, ChatReplyPreferenceService preferences,
            IdempotentRequestService requests, ResourceJson json) {
        this.identity = identity;
        this.preferences = preferences;
        this.requests = requests;
        this.json = json;
    }

    public PageResponse<ChatReplyPreferenceView> list(String enterprise, String cursor, Integer limit, HttpServletRequest request) {
        return preferences.list(identity.requirePermission(request.getSession(false), enterprise, "agent.run"), cursor, limit);
    }

    public ChatReplyPreferenceView get(String enterprise, String conversation, HttpServletRequest request) {
        return preferences.get(identity.requirePermission(request.getSession(false), enterprise, "agent.run"), conversation);
    }

    public ApiOperationResult save(String enterprise, String conversation, ChatReplyPreferenceRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChatReplyPreferenceRequest.class);
        var actor = identity.requirePermission(request.getSession(false), enterprise, "agent.run");
        return requests.execute(request, actor.user(), enterprise, Set.of("agent.run"),
            () -> preferences.owned(identity.requirePermission(request.getSession(false), enterprise, "agent.run"), conversation),
            () -> ApiOperationResult.of(200, json.object(json.tree(preferences.save(actor, conversation, input.outputVisibility(), input.expectedRevision())))));
    }
}
