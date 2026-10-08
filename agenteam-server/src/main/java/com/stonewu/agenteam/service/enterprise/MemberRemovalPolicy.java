package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceOwnershipPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MemberRemovalPolicy {
    private final EnterpriseAuthorizationService authorization;
    private final EnterpriseMapper members;
    private final PermissionMapper permissions;
    private final RoleAssignmentPolicy roles;
    private final ResourceOwnershipPolicy ownership;
    private final AccountBehaviorService behavior;

    public MemberRemovalPolicy(EnterpriseAuthorizationService authorization, EnterpriseMapper members,
                               PermissionMapper permissions, RoleAssignmentPolicy roles,
                               ResourceOwnershipPolicy ownership, AccountBehaviorService behavior) {
        this.authorization = authorization;
        this.members = members;
        this.permissions = permissions;
        this.roles = roles;
        this.ownership = ownership;
        this.behavior = behavior;
    }

    public String authorize(AuthContext actor, String member, boolean mutation, boolean removed) {
        behavior.requireOperation(member, AccountOperation.REMOVE_MEMBER);
        authorization.requireEnterpriseScope(
            mutation ? authorization.lockAndRequire(actor, "enterprise.members.manage") : authorization.require(actor,
                "enterprise.members.manage"));
        var value = members.findMember(actor.enterpriseId(), member)
            .filter(row -> removed || !row.status().equals("removed"))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        roles.validateExistingRoleAuthority(actor, value.roleIds());
        return value.userId();
    }

    public boolean canTransferOwnership(AuthContext actor, String member, MemberRemovalState state) {
        return ownership.canTransferOwned(actor, member, state.resourceKinds()) && (state.ownedTeams().isEmpty()
            || permissions.operationScope(actor.userId(), actor.enterpriseId(), "enterprise.teams.manage")
            .filter(scope -> scope == DataScope.ENTERPRISE).isPresent());
    }
}
