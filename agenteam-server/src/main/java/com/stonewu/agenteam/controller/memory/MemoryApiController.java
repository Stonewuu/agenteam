package com.stonewu.agenteam.controller.memory;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.memory.request.MemoryWriteRequest;
import com.stonewu.agenteam.model.memory.response.MemoryAgentView;
import com.stonewu.agenteam.model.memory.response.MemoryContextView;
import com.stonewu.agenteam.model.memory.response.MemoryView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.memory.MemoryApiService;
import com.stonewu.agenteam.service.memory.MemoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class MemoryApiController {

    private final AuthContextService identity;

    private final MemoryService memories;

    private final MemoryApiService changes;

    private final ApiResponses responses;

    public MemoryApiController(AuthContextService identity, MemoryService memories, MemoryApiService changes,
                               ApiResponses responses) {
        this.identity = identity;
        this.memories = memories;
        this.changes = changes;
        this.responses = responses;
    }

    @GetMapping("/memories/agents")
    public ApiResponse<PageResponse<MemoryAgentView>> agents(@PathVariable String enterpriseId,
                                                             @RequestParam(required = false) String query,
                                                             @RequestParam(required = false) String cursor,
                                                             @RequestParam(required = false) Integer limit,
                                                             HttpServletRequest request) {
        return responses.success(
            memories.agents(identity.requireEnterprise(request.getSession(false), enterpriseId), query, cursor, limit),
            request);
    }

    @GetMapping("/agents/{agentId}/memories/context")
    public ApiResponse<MemoryContextView> context(@PathVariable String enterpriseId, @PathVariable String agentId,
                                                  @RequestParam(required = false) String sourceMessageId,
                                                  HttpServletRequest request) {
        return responses.success(
            memories.context(identity.requireEnterprise(request.getSession(false), enterpriseId), agentId,
                sourceMessageId), request);
    }

    @GetMapping("/agents/{agentId}/memories")
    public ApiResponse<PageResponse<MemoryView>> list(@PathVariable String enterpriseId, @PathVariable String agentId,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) Integer limit,
                                                      HttpServletRequest request) {
        return responses.success(
            memories.list(identity.requireEnterprise(request.getSession(false), enterpriseId), agentId, cursor, limit),
            request);
    }

    @PostMapping("/agents/{agentId}/memories")
    public ResponseEntity<?> create(@PathVariable String enterpriseId, @PathVariable String agentId,
                                    @RequestBody MemoryWriteRequest value, HttpServletRequest request) {
        return responses.operation(changes.create(enterpriseId, agentId, value, request), request);
    }

    @PutMapping("/agents/{agentId}/memories/{memoryId}")
    public ResponseEntity<?> update(@PathVariable String enterpriseId, @PathVariable String agentId,
                                    @PathVariable String memoryId, @RequestBody MemoryWriteRequest value,
                                    HttpServletRequest request) {
        return responses.operation(changes.update(enterpriseId, agentId, memoryId, value, request), request);
    }

    @DeleteMapping("/agents/{agentId}/memories/{memoryId}")
    public ResponseEntity<?> delete(@PathVariable String enterpriseId, @PathVariable String agentId,
                                    @PathVariable String memoryId, HttpServletRequest request) {
        return responses.operation(changes.delete(enterpriseId, agentId, memoryId, request), request);
    }

    @DeleteMapping("/agents/{agentId}/memories")
    public ResponseEntity<?> clear(@PathVariable String enterpriseId, @PathVariable String agentId,
                                   HttpServletRequest request) {
        return responses.operation(changes.clear(enterpriseId, agentId, request), request);
    }
}
