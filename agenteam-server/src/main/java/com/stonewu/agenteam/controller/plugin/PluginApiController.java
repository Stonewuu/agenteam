package com.stonewu.agenteam.controller.plugin;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.plugin.response.BuiltinPluginView;
import com.stonewu.agenteam.model.plugin.response.PluginToolView;
import com.stonewu.agenteam.model.plugin.response.ToolSourceView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.plugin.PluginCheckApiService;
import com.stonewu.agenteam.service.plugin.PluginQueryService;
import com.stonewu.agenteam.service.plugin.ToolSourceCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 插件检查与工具列表的协议入口，执行规则由对应服务负责。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/plugins")
public class PluginApiController {

    private final PluginCheckApiService checks;

    private final PluginQueryService queries;

    private final AuthContextService identity;

    private final ApiResponses responses;

    private final ToolSourceCatalogService sources;

    public PluginApiController(PluginCheckApiService checks, PluginQueryService queries, AuthContextService identity,
                               ApiResponses responses, ToolSourceCatalogService sources) {
        this.checks = checks;
        this.queries = queries;
        this.identity = identity;
        this.responses = responses;
        this.sources = sources;
    }

    @GetMapping("/tool-sources")
    public ApiResponse<List<ToolSourceView>> sources(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(sources.list(identity.requireEnterprise(request.getSession(false), enterpriseId)),
            request);
    }

    @GetMapping("/tool-sources/{resourceId}/versions/{versionId}")
    public ApiResponse<ToolSourceView> sourceVersion(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                     @PathVariable String versionId, HttpServletRequest request) {
        return responses.success(
            sources.version(identity.requireEnterprise(request.getSession(false), enterpriseId), resourceId, versionId),
            request);
    }

    @GetMapping("/builtins")
    public ApiResponse<List<BuiltinPluginView>> builtins(@PathVariable String enterpriseId,
                                                         HttpServletRequest request) {
        return responses.success(queries.builtins(identity.requireEnterprise(request.getSession(false), enterpriseId)),
            request);
    }

    @GetMapping("/{resourceId}/tools")
    public ApiResponse<List<PluginToolView>> tools(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                   HttpServletRequest request) {
        return responses.success(
            queries.tools(identity.requireEnterprise(request.getSession(false), enterpriseId), resourceId), request);
    }

    @PostMapping("/{resourceId}/check")
    public ResponseEntity<ApiResponse<Object>> check(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                     HttpServletRequest request) {
        return responses.operation(checks.check(enterpriseId, resourceId, request), request);
    }
}
