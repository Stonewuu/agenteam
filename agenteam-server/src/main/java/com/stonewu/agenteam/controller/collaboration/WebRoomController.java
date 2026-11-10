package com.stonewu.agenteam.controller.collaboration;

import com.stonewu.agenteam.model.collaboration.request.WebRoomCreateRequest;
import com.stonewu.agenteam.model.collaboration.request.WebRoomMessageRequest;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.collaboration.WebRoomApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/collaboration")
public class WebRoomController {
    private final WebRoomApiService service;
    private final ApiResponses responses;

    public WebRoomController(WebRoomApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping("/room-candidates")
    public ApiResponse<?> candidates(@PathVariable String enterpriseId, @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        return responses.success(service.candidates(enterpriseId, query, cursor, request), request);
    }

    @GetMapping("/directory")
    public ApiResponse<?> directory(@PathVariable String enterpriseId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String query, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(service.directory(enterpriseId, status, query, cursor, limit, request), request);
    }

    @GetMapping("/rooms")
    public ApiResponse<?> list(@PathVariable String enterpriseId, @RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        return responses.success(service.list(enterpriseId, page, request), request);
    }

    @GetMapping("/rooms/{id}")
    public ApiResponse<?> room(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestParam(defaultValue = "0") long before, HttpServletRequest request) {
        return responses.success(service.room(enterpriseId, id, before, request), request);
    }

    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId, @RequestBody WebRoomCreateRequest input,
            HttpServletRequest request) {
        return responses.operation(service.create(enterpriseId, input, request), request);
    }

    @PostMapping("/rooms/{id}/queue/{entry}/prioritize")
    public ResponseEntity<ApiResponse<Object>> prioritize(@PathVariable String enterpriseId, @PathVariable String id,
            @PathVariable String entry, HttpServletRequest request) {
        return responses.operation(service.changeQueue(enterpriseId, id, entry, true, request), request);
    }

    @DeleteMapping("/rooms/{id}/queue/{entry}")
    public ResponseEntity<ApiResponse<Object>> withdraw(@PathVariable String enterpriseId, @PathVariable String id,
            @PathVariable String entry, HttpServletRequest request) {
        return responses.operation(service.changeQueue(enterpriseId, id, entry, false, request), request);
    }

    @PostMapping("/rooms/{id}/messages")
    public ResponseEntity<ApiResponse<Object>> send(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody WebRoomMessageRequest input, HttpServletRequest request) {
        return responses.operation(service.send(enterpriseId, id, input, request), request);
    }
}
