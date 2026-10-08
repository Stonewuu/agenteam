package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.model.enterprise.response.*;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.enterprise.EnterpriseMetadataService;
import com.stonewu.agenteam.service.enterprise.OrganizationQueryService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组织读取请求只接受明确的企业路径，不修改会话中的企业偏好。
 */
@RestController
@RequestMapping("/api/v1/enterprises")
public class OrganizationQueryApiController {

    private final AuthContextService identity;

    private final OrganizationQueryService query;

    private final EnterpriseMetadataService enterprises;

    private final ApiResponses responses;

    public OrganizationQueryApiController(AuthContextService identity, OrganizationQueryService query,
                                          EnterpriseMetadataService enterprises, ApiResponses responses) {
        this.identity = identity;
        this.query = query;
        this.enterprises = enterprises;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<EnterpriseView>> enterprises(HttpServletRequest request) {
        return responses.success(enterprises.list(identity.requireUser(request.getSession(false))), request);
    }

    @GetMapping("/{enterpriseId}")
    public ApiResponse<EnterpriseView> enterprise(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(enterprises.get(identity.requireEnterprise(request.getSession(false), enterpriseId)),
            request);
    }

    @GetMapping("/{enterpriseId}/members")
    public ApiResponse<PageResponse<MemberView>> members(@PathVariable String enterpriseId,
                                                         @RequestParam(required = false) String cursor,
                                                         @RequestParam(required = false) Integer limit,
                                                         @RequestParam(required = false) String query,
                                                         @RequestParam(required = false) String status,
                                                         HttpServletRequest request) {
        return responses.success(
            this.query.members(identity.requireEnterprise(request.getSession(false), enterpriseId), null, cursor, limit,
                query, status), request);
    }

    @GetMapping("/{enterpriseId}/members/{userId}")
    public ApiResponse<MemberView> member(@PathVariable String enterpriseId, @PathVariable String userId,
                                          HttpServletRequest request) {
        return responses.success(
            query.member(identity.requireEnterprise(request.getSession(false), enterpriseId), userId), request);
    }

    @GetMapping("/{enterpriseId}/teams")
    public ApiResponse<PageResponse<TeamView>> teams(@PathVariable String enterpriseId,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) String query,
                                                     HttpServletRequest request) {
        return responses.success(
            this.query.teams(identity.requireEnterprise(request.getSession(false), enterpriseId), cursor, limit, query),
            request);
    }

    @GetMapping("/{enterpriseId}/teams/{teamId}")
    public ApiResponse<TeamView> team(@PathVariable String enterpriseId, @PathVariable String teamId,
                                      HttpServletRequest request) {
        return responses.success(
            query.team(identity.requireEnterprise(request.getSession(false), enterpriseId), teamId), request);
    }

    @GetMapping("/{enterpriseId}/teams/{teamId}/members")
    public ApiResponse<PageResponse<MemberView>> teamMembers(@PathVariable String enterpriseId,
                                                             @PathVariable String teamId,
                                                             @RequestParam(required = false) String cursor,
                                                             @RequestParam(required = false) Integer limit,
                                                             HttpServletRequest request) {
        return responses.success(
            query.members(identity.requireEnterprise(request.getSession(false), enterpriseId), teamId, cursor, limit,
                null, null), request);
    }

    @GetMapping("/{enterpriseId}/roles")
    public ApiResponse<PageResponse<RoleView>> roles(@PathVariable String enterpriseId,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) String query,
                                                     HttpServletRequest request) {
        return responses.success(
            this.query.roles(identity.requireEnterprise(request.getSession(false), enterpriseId), cursor, limit, query),
            request);
    }

    @GetMapping("/{enterpriseId}/roles/{roleId}")
    public ApiResponse<RoleView> role(@PathVariable String enterpriseId, @PathVariable String roleId,
                                      HttpServletRequest request) {
        return responses.success(
            query.role(identity.requireEnterprise(request.getSession(false), enterpriseId), roleId), request);
    }

    @GetMapping("/{enterpriseId}/permissions")
    public ApiResponse<List<PermissionView>> permissions(@PathVariable String enterpriseId,
                                                         HttpServletRequest request) {
        return responses.success(query.permissions(identity.requireEnterprise(request.getSession(false), enterpriseId)),
            request);
    }
}
