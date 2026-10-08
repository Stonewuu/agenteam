package com.stonewu.agenteam.controller.resource;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.request.*;
import com.stonewu.agenteam.model.resource.response.ResourceDetailView;
import com.stonewu.agenteam.model.resource.response.ResourceSummaryView;
import com.stonewu.agenteam.model.resource.response.ResourceVersionView;
import com.stonewu.agenteam.model.resource.response.VersionSummaryView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.resource.ResourceMutationApiService;
import com.stonewu.agenteam.service.resource.ResourceQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 接收资源草稿与发布请求，由服务执行对应类型的权限、版本和事务检查。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/resources")
public class ResourceApiController {

    private final ResourceMutationApiService mutations;

    private final ResourceQueryService queries;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public ResourceApiController(ResourceMutationApiService mutations, ResourceQueryService queries,
                                 AuthContextService identity, ApiResponses responses) {
        this.mutations = mutations;
        this.queries = queries;
        this.identity = identity;
        this.responses = responses;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
                                                      @RequestBody ResourceCreateRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(mutations.create(enterpriseId, body, request), request);
    }

    @GetMapping
    public ApiResponse<PageResponse<ResourceSummaryView>> list(@PathVariable String enterpriseId,
                                                               @RequestParam String kind,
                                                               @RequestParam(required = false) String query,
                                                               @RequestParam(required = false) String status,
                                                               @RequestParam(required = false) String source,
                                                               @RequestParam(required = false) String subtype,
                                                               @RequestParam(required = false) List<String> tagIds,
                                                               @RequestParam(required = false) String sort,
                                                               @RequestParam(required = false) String cursor,
                                                               @RequestParam(required = false) Integer limit,
                                                               HttpServletRequest request) {
        return responses.success(queries.list(identity.requireEnterprise(request.getSession(false), enterpriseId),
            new ResourceListQuery(kind, query, status, source, subtype, tagIds, sort, cursor, limit)), request);
    }

    @GetMapping("/{resourceId}")
    public ApiResponse<ResourceDetailView> detail(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                  HttpServletRequest request) {
        return responses.success(
            queries.detail(identity.requireEnterprise(request.getSession(false), enterpriseId), resourceId), request);
    }

    @PutMapping("/{resourceId}/draft")
    public ResponseEntity<ApiResponse<Object>> draft(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                     @RequestBody DraftWriteRequest body, HttpServletRequest request) {
        return responses.operation(mutations.draft(enterpriseId, resourceId, body, request), request);
    }

    @PostMapping("/{resourceId}/publish")
    public ResponseEntity<ApiResponse<Object>> publish(@PathVariable String enterpriseId,
                                                       @PathVariable String resourceId,
                                                       @RequestBody ResourcePublishRequest body,
                                                       HttpServletRequest request) {
        return responses.operation(mutations.publish(enterpriseId, resourceId, body, request), request);
    }

    @GetMapping("/{resourceId}/versions")
    public ApiResponse<PageResponse<VersionSummaryView>> versions(@PathVariable String enterpriseId,
                                                                  @PathVariable String resourceId,
                                                                  @RequestParam(required = false) String cursor,
                                                                  @RequestParam(required = false) Integer limit,
                                                                  HttpServletRequest request) {
        return responses.success(
            queries.versions(identity.requireEnterprise(request.getSession(false), enterpriseId), resourceId, cursor,
                limit), request);
    }

    @GetMapping("/{resourceId}/versions/{versionId}")
    public ApiResponse<ResourceVersionView> version(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                    @PathVariable String versionId, HttpServletRequest request) {
        return responses.success(
            queries.version(identity.requireEnterprise(request.getSession(false), enterpriseId), resourceId, versionId),
            request);
    }

    @PostMapping("/{resourceId}/copy")
    public ResponseEntity<ApiResponse<Object>> copy(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                    @RequestBody CopyResourceRequest body, HttpServletRequest request) {
        return responses.operation(mutations.copy(enterpriseId, resourceId, body, request), request);
    }

    @PostMapping("/{resourceId}/versions/{versionId}/load-draft")
    public ResponseEntity<ApiResponse<Object>> loadVersion(@PathVariable String enterpriseId,
                                                           @PathVariable String resourceId,
                                                           @PathVariable String versionId, HttpServletRequest request) {
        return responses.operation(mutations.loadVersion(enterpriseId, resourceId, versionId, request), request);
    }
}
