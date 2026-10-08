package com.stonewu.agenteam.mapper.enterprise;

import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.permission.MemberRoleQueryMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.todo.TodoTableMapper;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState.ResourceItem;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState.RunItem;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState.TodoItem;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState.VersionedItem;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 管理预览只读取实际数量和版本，交接写入仍由企业事务统一提交。
 */
@Repository
public class MemberRemovalMapper {
    private record Subject(long revision, String memberStatus, String accountStatus, long permissionVersion) {
    }

    private final MemberRemovalSqlMapper statements;
    private final PermissionMapper permissions;

    private final MemberRoleQueryMapper memberRoleQueryMapper;

    private final MemberTeamSqlMapper memberTeamSqlMapper;

    private final EnterpriseTeamTableMapper enterpriseTeamTableMapper;

    private final TodoTableMapper todoTableMapper;

    private final RunSqlMapper runSqlMapper;

    private final ScheduleSqlMapper scheduleSqlMapper;

    private final IdentityQueryMapper identityQueryMapper;

    public MemberRemovalMapper(MemberRemovalSqlMapper statements, PermissionMapper permissions,
                               MemberRoleQueryMapper memberRoleQueryMapper, MemberTeamSqlMapper memberTeamSqlMapper,
                               EnterpriseTeamTableMapper enterpriseTeamTableMapper, TodoTableMapper todoTableMapper,
                               RunSqlMapper runSqlMapper, ScheduleSqlMapper scheduleSqlMapper,
                               IdentityQueryMapper identityQueryMapper) {
        this.identityQueryMapper = identityQueryMapper;
        this.scheduleSqlMapper = scheduleSqlMapper;
        this.runSqlMapper = runSqlMapper;
        this.todoTableMapper = todoTableMapper;
        this.enterpriseTeamTableMapper = enterpriseTeamTableMapper;
        this.memberTeamSqlMapper = memberTeamSqlMapper;
        this.memberRoleQueryMapper = memberRoleQueryMapper;
        this.statements = statements;
        this.permissions = permissions;
    }

    public Optional<MemberRemovalState> load(String enterprise, String user) {
        var subject = identityQueryMapper.loadEnterpriseMember(enterprise, user).stream().map(
            row -> new Subject(row.getRevision(), row.getMemberStatus(), row.getAccountStatus(),
                row.getPermissionVersion())).findFirst();
        if (subject.isEmpty()) {
            return Optional.empty();
        }
        var value = subject.get();
        var roles = memberRoleQueryMapper.loadSysUserRole(enterprise, user);
        var teams = memberTeamSqlMapper.loadEnterpriseTeamMember(enterprise, user);
        boolean last = permissions.isEnterpriseAdmin(user, enterprise) && permissions.activeAdministratorCount(
            enterprise) == 1;
        return Optional.of(new MemberRemovalState(value.revision(), value.memberStatus(), value.accountStatus(),
            value.permissionVersion(), roles, teams,
            resources(enterprise, user), ownedTeams(enterprise, user), todos(enterprise, user), runs(enterprise, user),
            schedules(enterprise, user), last));
    }

    private List<ResourceItem> resources(String enterprise, String user) {
        return statements.resourcesResource(enterprise, user).stream()
            .map(row -> new ResourceItem(row.getId(), ResourceKind.from(row.getKind()), row.getRevision())).toList();
    }

    private List<VersionedItem> ownedTeams(String enterprise, String user) {
        return enterpriseTeamTableMapper.ownedTeamsEnterpriseTeam(enterprise, user).stream()
            .map(row -> new VersionedItem(row.getId(), row.getRevision())).toList();
    }

    private List<TodoItem> todos(String enterprise, String user) {
        return todoTableMapper.todosTodoItem(enterprise, user).stream()
            .map(row -> new TodoItem(row.getId(), row.getRevision(), row.getTeamId())).toList();
    }

    private List<RunItem> runs(String enterprise, String user) {
        return runSqlMapper.runsAgentRun(enterprise, user).stream()
            .map(row -> new RunItem(row.getId(), row.getStatus(), row.getCurrentAttemptNo(), row.getLeaseVersion()))
            .toList();
    }

    private List<VersionedItem> schedules(String enterprise, String user) {
        return scheduleSqlMapper.schedulesScheduledTask(enterprise, user).stream()
            .map(row -> new VersionedItem(row.getId(), row.getRevision())).toList();
    }

    public void transferResources(String enterprise, String user, String recipient, int expected, Instant now) {
        changed(statements.transferResourcesResource(recipient, timestamp(now), enterprise, user), expected);
    }

    public void transferTeams(String enterprise, String user, String recipient, int expected, Instant now) {
        changed(enterpriseTeamTableMapper.transferTeamsEnterpriseTeam(recipient, timestamp(now), enterprise, user),
            expected);
    }

    private void changed(int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException("成员关联内容已变化，本次交接未提交");
        }
    }
}
