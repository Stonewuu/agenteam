package com.stonewu.agenteam.mapper.todo;

import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamSqlMapper;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 待办引用只检查当前企业内的有效身份与实际执行，不读取私有来源正文。
 */
@Repository
public class TodoRelationMapper {
    private final TodoRelationSqlMapper statements;

    private final IdentityQueryMapper identityQueryMapper;

    private final MemberTeamSqlMapper memberTeamSqlMapper;

    public TodoRelationMapper(TodoRelationSqlMapper statements, IdentityQueryMapper identityQueryMapper,
                              MemberTeamSqlMapper memberTeamSqlMapper) {
        this.memberTeamSqlMapper = memberTeamSqlMapper;
        this.identityQueryMapper = identityQueryMapper;
        this.statements = statements;
    }

    public Optional<String> activeMemberId(String enterprise, String user) {
        return identityQueryMapper.activeMemberIdEnterpriseMember(enterprise, user).stream().findFirst();
    }

    public boolean activeTeamMember(String enterprise, String team, String user) {
        return activeTeamId(enterprise, team, user).isPresent();
    }

    public Optional<String> activeTeamId(String enterprise, String team, String user) {
        return memberTeamSqlMapper.activeTeamIdEnterpriseTeamMember(enterprise, team, user).stream().findFirst();
    }

    public boolean hasStartedWorkflow(String enterprise, String run) {
        return Boolean.TRUE.equals(
            DataAccessUtils.nullableSingleResult(statements.hasStartedWorkflowRunStep(enterprise, run)));
    }
}
