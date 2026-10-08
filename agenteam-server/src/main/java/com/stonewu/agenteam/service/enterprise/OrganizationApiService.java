package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.request.*;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.PatchFields;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 新版组织修改的路径身份、原始版本和重复请求约束。
 */
@Service
public class OrganizationApiService {
    private final AuthContextService context;
    private final IdempotentRequestService operations;
    private final EnterpriseMetadataService enterprises;
    private final TeamDefinitionService teams;
    private final MemberDefinitionService members;

    public OrganizationApiService(AuthContextService context, IdempotentRequestService operations,
                                  EnterpriseMetadataService enterprises,
                                  TeamDefinitionService teams,
                                  MemberDefinitionService members) {
        this.context = context;
        this.operations = operations;
        this.enterprises = enterprises;
        this.teams = teams;
        this.members = members;
    }

    public ApiOperationResult enterprise(String enterpriseId, EnterpriseUpdatePayload value,
                                         HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var fields = PatchFields.read(request, Set.of("contactEmail"));
        return executeMutation(enterpriseId, request, Set.of(), enterprises::authorizeUpdate,
            actor -> ApiOperationResult.of(200, enterprises.update(actor, value, revision, fields)));
    }

    public ApiOperationResult createTeam(String enterpriseId, TeamWritePayload value, HttpServletRequest request) {
        return executeMutation(enterpriseId, request, Set.of("/memberIds"), teams::authorize,
            actor -> ApiOperationResult.of(201, teams.create(actor, value)));
    }

    public ApiOperationResult team(String enterpriseId, String id, TeamWritePayload value, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return executeMutation(enterpriseId, request, Set.of("/memberIds"), teams::authorize,
            actor -> ApiOperationResult.of(200, teams.update(actor, id, value, revision)));
    }

    public ApiOperationResult teamMembers(String enterpriseId, String id, List<String> ids,
                                          HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return executeMutation(enterpriseId, request, Set.of("/memberIds"), teams::authorize,
            actor -> ApiOperationResult.of(200, teams.members(actor, id, ids, revision)));
    }

    public ApiOperationResult teamStatus(String enterpriseId, String id, String value, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return executeMutation(enterpriseId, request, Set.of(), teams::authorize,
            actor -> ApiOperationResult.of(200, teams.status(actor, id, value, revision)));
    }

    public ApiOperationResult deleteTeam(String enterpriseId, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return executeMutation(enterpriseId, request, Set.of(), teams::authorize, actor -> {
            teams.delete(actor, id, revision);
            return ApiOperationResult.of(200, Map.of("success", true));
        });
    }

    public ApiOperationResult member(String enterpriseId, String id, MemberUpdatePayload value,
                                     HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var fields = PatchFields.read(request, Set.of());
        return executeMutation(enterpriseId, request, Set.of("/roleIds", "/teamIds"), actor -> members.authorize(actor, id),
            actor -> ApiOperationResult.of(200, members.update(actor, id, value, revision, fields)));
    }

    public ApiOperationResult memberStatus(String enterpriseId, String id, String value, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return executeMutation(enterpriseId, request, Set.of(), actor -> members.authorize(actor, id),
            actor -> ApiOperationResult.of(200, members.status(actor, id, value, revision)));
    }

    public ApiOperationResult executeMutation(String enterpriseId, HttpServletRequest request, Set<String> unordered,
                                      Consumer<AuthContext> authorize,
                                      Function<AuthContext, ApiOperationResult> action) {
        var actor = context.requireEnterprise(request.getSession(false), enterpriseId);
        return operations.execute(request, actor.user(), enterpriseId, unordered, () -> authorize.accept(actor),
            () -> action.apply(actor));
    }

}
