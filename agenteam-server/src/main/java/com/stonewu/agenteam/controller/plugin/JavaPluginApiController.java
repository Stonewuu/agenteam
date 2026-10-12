package com.stonewu.agenteam.controller.plugin;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.plugin.response.JavaPluginView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.plugin.JavaPluginInstallationTransactions;
import com.stonewu.agenteam.service.plugin.JavaPluginManagementService;
import com.stonewu.agenteam.service.plugin.JavaPluginRegistry;
import com.stonewu.agenteam.service.plugin.JavaPluginReferenceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Java 程序包在插件配置页管理，实际权限及版本变更由服务层执行。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/plugins/java-packages")
public class JavaPluginApiController {
    private final AuthContextService identity;
    private final ApiResponses responses;
    private final JavaPluginManagementService management;
    private final JavaPluginInstallationTransactions transactions;
    private final JavaPluginRegistry registry;
    private final JavaPluginReferenceService references;

    public JavaPluginApiController(AuthContextService identity, ApiResponses responses, JavaPluginManagementService management,
                                    JavaPluginInstallationTransactions transactions, JavaPluginRegistry registry,
                                    JavaPluginReferenceService references) {
        this.identity = identity;
        this.responses = responses;
        this.management = management;
        this.transactions = transactions;
        this.registry = registry;
        this.references = references;
    }

    public record Catalog(boolean canManage, List<JavaPluginView> items) {
    }

    public record Activation(String previousInstallationId) {
    }

    @GetMapping("/references")
    public ApiResponse<List<JavaPluginReferenceService.Notice>> references(@PathVariable String enterpriseId,
        @RequestParam List<String> versionIds, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        management.authorize(actor, false);
        return responses.success(references.notices(actor, versionIds), request);
    }

    @GetMapping
    public ApiResponse<Catalog> list(@PathVariable String enterpriseId,
                                      @RequestParam(required = false) String include, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        management.authorize(actor, false);
        return responses.success(new Catalog(actor.superAdmin(), registry.list(enterpriseId, include)), request);
    }

    @PutMapping(value = "/content", consumes = "application/octet-stream")
    public ApiResponse<JavaPluginView> upload(@PathVariable String enterpriseId, HttpServletRequest request) throws IOException {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        return responses.success(management.prepare(actor, RequestPreconditions.requestKey(request), request.getInputStream()), request);
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<JavaPluginView> activate(@PathVariable String enterpriseId, @PathVariable String id,
                                                @RequestBody Activation input, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        management.authorize(actor, true);
        return responses.success(transactions.activate(actor, id, input.previousInstallationId()), request);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Boolean>> uninstall(@PathVariable String enterpriseId, @PathVariable String id,
                                                       HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        management.authorize(actor, true);
        transactions.uninstall(actor, id);
        return responses.success(Map.of("success", true), request);
    }
}
