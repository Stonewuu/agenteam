package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.request.WebRoomCreateRequest;
import com.stonewu.agenteam.model.collaboration.request.WebRoomMessageRequest;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class WebRoomApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final WebRoomService rooms;
    private final WebRoomRoster roster;
    private final CollaborationTaskListService directory;

    public WebRoomApiService(AuthContextService identity, IdempotentRequestService requests, WebRoomService rooms, WebRoomRoster roster, CollaborationTaskListService directory) {
        this.directory = directory;
        this.identity = identity;
        this.requests = requests;
        this.rooms = rooms;
        this.roster = roster;
    }

    private AuthContext actor(String enterprise, HttpServletRequest request) {
        return identity.requirePermission(request.getSession(false), enterprise, "agent.run");
    }

    public PageResponse<WebRoomRoster.Candidate> candidates(String enterprise, String query, String cursor, HttpServletRequest request) {
        return roster.candidates(actor(enterprise, request), query, cursor);
    }

    public PageResponse<CollaborationTaskListService.DirectoryEntry> directory(String enterprise, String status, String query,
            String cursor, Integer limit, HttpServletRequest request) {
        return directory.directory(actor(enterprise, request), status, query, cursor, limit);
    }

    public List<WebRoomService.Summary> list(String enterprise, int page, HttpServletRequest request) {
        return rooms.list(actor(enterprise, request), page);
    }

    public WebRoomService.Detail room(String enterprise, String room, long before, HttpServletRequest request) {
        return rooms.detail(actor(enterprise, request), room, before);
    }

    public ApiOperationResult create(String enterprise, WebRoomCreateRequest input, HttpServletRequest request) {
        InputValidation.request(request, WebRoomCreateRequest.class);
        var actor = actor(enterprise, request);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> actor(enterprise, request),
            () -> ApiOperationResult.of(201, rooms.create(actor, input)));
    }

    public ApiOperationResult changeQueue(String enterprise, String room, String entry, boolean prioritize, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> rooms.owned(actor(enterprise, request), room),
            () -> {
                rooms.changeQueue(actor, room, entry, prioritize);
                return ApiOperationResult.of(200, entry);
            });
    }

    public ApiOperationResult send(String enterprise, String room, WebRoomMessageRequest input, HttpServletRequest request) {
        InputValidation.request(request, WebRoomMessageRequest.class);
        var actor = actor(enterprise, request);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> rooms.owned(actor(enterprise, request), room),
            () -> ApiOperationResult.of(202, rooms.send(actor, room, input)));
    }
}
