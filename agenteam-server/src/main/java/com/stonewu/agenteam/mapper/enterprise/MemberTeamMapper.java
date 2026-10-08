package com.stonewu.agenteam.mapper.enterprise;

import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;


import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 在已锁定企业的事务中替换成员团队，保留没有变化的加入时间。
 */
@Repository
public class MemberTeamMapper {
    private final MemberTeamSqlMapper statements;

    private final EnterpriseTeamTableMapper enterpriseTeamTableMapper;

    private final IdentityQueryMapper identityQueryMapper;

    public MemberTeamMapper(MemberTeamSqlMapper statements, EnterpriseTeamTableMapper enterpriseTeamTableMapper,
                            IdentityQueryMapper identityQueryMapper) {
        this.identityQueryMapper = identityQueryMapper;
        this.enterpriseTeamTableMapper = enterpriseTeamTableMapper;
        this.statements = statements;
    }

    public int count(String enterpriseId, String teamId) {
        return DataAccessUtils.nullableSingleResult(statements.countEnterpriseTeamMember(enterpriseId, teamId));
    }

    public Set<String> teams(String enterpriseId, String userId) {
        return new HashSet<>(statements.teamsEnterpriseTeamMember(enterpriseId, userId));
    }

    public void replace(String enterpriseId, String userId, Set<String> requested, Instant now) {
        Set<String> current = teams(enterpriseId, userId);
        for (String id : current) {
            if (!requested.contains(id)) {
                statements.replaceEnterpriseTeamMember(enterpriseId, id, userId);
                changedTeam(enterpriseId, id, now);
            }
        }
        for (String id : requested) {
            if (!current.contains(id)) {
                statements.addTeamMember(enterpriseId, id, userId, Timestamp.from(now));
                changedTeam(enterpriseId, id, now);
            }
        }
    }

    private void changedTeam(String enterpriseId, String id, Instant now) {
        enterpriseTeamTableMapper.changedTeamEnterpriseTeam(Timestamp.from(now), enterpriseId, id);
    }

    public void rejoin(String enterpriseId, String userId, String name, Instant now) {
        int changed = identityQueryMapper.rejoinEnterpriseMember(name, Timestamp.from(now), enterpriseId, userId);
        if (changed != 1) {
            throw new IllegalStateException("成员状态已变化，重新加入未提交");
        }
    }
}
