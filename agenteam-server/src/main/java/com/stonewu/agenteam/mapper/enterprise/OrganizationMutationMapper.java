package com.stonewu.agenteam.mapper.enterprise;


import com.stonewu.agenteam.model.enterprise.request.EnterpriseUpdatePayload;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 组织定义写入统一递增修改版本；关联变更由上层企业事务编排。
 */
@Repository
public class OrganizationMutationMapper {
    private final EnterpriseTableMapper enterpriseTableMapper;
    private final EnterpriseTeamTableMapper teams;

    public OrganizationMutationMapper(EnterpriseTableMapper enterpriseTableMapper, EnterpriseTeamTableMapper teams) {
        this.enterpriseTableMapper = enterpriseTableMapper;
        this.teams = teams;
    }

    public void enterprise(String id, EnterpriseUpdatePayload values, String pendingQuotaTimezone, long revision,
                           Instant now) {
        changed(enterpriseTableMapper.enterpriseEnterprise(values.name(), values.description(), values.contactEmail(),
            values.timezone(), pendingQuotaTimezone, values.retentionDays(), Timestamp.from(now), id, revision));
    }

    public void deleteTeam(String enterpriseId, String id, long revision, Instant now) {
        changed(teams.deleteTeam(enterpriseId, id, revision, now));
    }

    private void changed(int count) {
        if (count != 1) {
            throw new IllegalStateException("组织资料已经变化，当前修改未提交");
        }
    }
}
