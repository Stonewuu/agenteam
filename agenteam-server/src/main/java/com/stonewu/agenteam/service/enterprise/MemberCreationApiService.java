package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.model.enterprise.request.MemberCreateRequest;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 会话校验与重复提交处理集中在服务层。
 */
@Service
public class MemberCreationApiService {
    private final AuthContextService identity;
    private final InvitationManagementService management;
    private final MemberCreationService members;
    private final IdempotentRequestService requests;

    public MemberCreationApiService(AuthContextService identity, InvitationManagementService management,
                                    MemberCreationService members, IdempotentRequestService requests) {
        this.identity = identity;
        this.management = management;
        this.members = members;
        this.requests = requests;
    }

    public ApiOperationResult create(String enterpriseId, MemberCreateRequest payload, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        return requests.execute(request, actor.user(), enterpriseId, Set.of("/roleIds", "/teamIds"),
            () -> management.authorize(actor), () -> ApiOperationResult.of(201, members.create(actor, payload)));
    }
}
