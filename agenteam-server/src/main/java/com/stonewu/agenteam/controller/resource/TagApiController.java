package com.stonewu.agenteam.controller.resource;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.request.TagWriteRequest;
import com.stonewu.agenteam.model.resource.response.TagView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.resource.TagApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/tags")
public class TagApiController {

    private final TagApiService tags;

    private final ApiResponses responses;

    public TagApiController(TagApiService tags, ApiResponses responses) {
        this.tags = tags;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<TagView>> list(@PathVariable String enterpriseId,
                                                   @RequestParam(required = false) String query,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit,
                                                   HttpServletRequest request) {
        return responses.success(tags.list(enterpriseId, query, cursor, limit, request), request);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
                                                      @RequestBody TagWriteRequest body, HttpServletRequest request) {
        return responses.operation(tags.create(enterpriseId, body.name(), request), request);
    }

    @PatchMapping("/{tagId}")
    public ResponseEntity<ApiResponse<Object>> rename(@PathVariable String enterpriseId, @PathVariable String tagId,
                                                      @RequestBody TagWriteRequest body, HttpServletRequest request) {
        return responses.operation(tags.rename(enterpriseId, tagId, body.name(), request), request);
    }

    @DeleteMapping("/{tagId}")
    public ResponseEntity<ApiResponse<Object>> delete(@PathVariable String enterpriseId, @PathVariable String tagId,
                                                      HttpServletRequest request) {
        return responses.operation(tags.delete(enterpriseId, tagId, request), request);
    }
}
