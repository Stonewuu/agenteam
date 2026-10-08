package com.stonewu.agenteam.mapper.enterprise;

import com.stonewu.agenteam.service.enterprise.OrganizationDependencies;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 身份、资源授权与次数规则已经存在的组织依赖，按当前企业查询实际数量。
 */
@Repository
public class OrganizationDependencyMapper implements OrganizationDependencies {
    private final OrganizationDependencySqlMapper statements;
    private final Clock clock;

    public OrganizationDependencyMapper(OrganizationDependencySqlMapper statements, Clock clock) {
        this.statements = statements;
        this.clock = clock;
    }

    @Override
    public Map<String, Long> role(String enterpriseId, String roleId) {
        var row = statements.role(enterpriseId, roleId, clock.instant());
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("members", row.members());
        counts.put("pendingInvitations", row.pendingInvitations());
        counts.put("quotaPolicies", row.quotaPolicies());
        return counts;
    }

    @Override
    public Map<String, Long> team(String enterpriseId, String teamId) {
        var row = statements.team(enterpriseId, teamId, clock.instant());
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("members", row.members());
        counts.put("resourceGrants", row.resourceGrants());
        counts.put("pendingInvitations", row.pendingInvitations());
        counts.put("quotaPolicies", row.quotaPolicies());
        return counts;
    }
}
