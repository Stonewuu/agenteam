package com.stonewu.agenteam.mapper.enterprise;

import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.permission.MemberRoleQueryMapper;
import com.stonewu.agenteam.mapper.usage.QuotaPolicySqlMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseEntity;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseQueryRow;
import com.stonewu.agenteam.model.enterprise.response.EnterpriseMemberResponse;
import com.stonewu.agenteam.model.enterprise.response.EnterpriseTeamResponse;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * 企业资料和组织关系的数据访问，关联读写必须包含企业编号。
 */
@Repository
public class EnterpriseMapper {

    private final EnterpriseSqlMapper statements;

    private final QuotaPolicySqlMapper quotaPolicySqlMapper;

    private final IdentityQueryMapper identityQueryMapper;

    private final EnterpriseTeamTableMapper enterpriseTeamTableMapper;

    private final MemberTeamSqlMapper memberTeamSqlMapper;

    private final MemberRoleQueryMapper memberRoleQueryMapper;

    public EnterpriseMapper(EnterpriseSqlMapper statements, QuotaPolicySqlMapper quotaPolicySqlMapper,
                            IdentityQueryMapper identityQueryMapper,
                            EnterpriseTeamTableMapper enterpriseTeamTableMapper,
                            MemberTeamSqlMapper memberTeamSqlMapper, MemberRoleQueryMapper memberRoleQueryMapper) {
        this.memberRoleQueryMapper = memberRoleQueryMapper;
        this.memberTeamSqlMapper = memberTeamSqlMapper;
        this.enterpriseTeamTableMapper = enterpriseTeamTableMapper;
        this.identityQueryMapper = identityQueryMapper;
        this.quotaPolicySqlMapper = quotaPolicySqlMapper;
        this.statements = statements;
    }

    public Optional<EnterpriseEntity> findById(String enterpriseId) {
        return statements.findByIdEnterprise(enterpriseId).stream().map(this::mapEnterprise).toList()
            .stream().findFirst();
    }

    public Optional<String> lockEnterprise(String enterpriseId) {
        return statements.lockEnterpriseEnterprise(enterpriseId).stream().findFirst();
    }

    public String timezone(String enterpriseId) {
        return DataAccessUtils.nullableSingleResult(statements.timezoneEnterprise(enterpriseId));
    }

    public void insert(String enterpriseId, String name, String description, String email, String createdBy,
                       QuotaPeriod period, Instant now) {
        statements.insertEnterprise(enterpriseId, name, description, email, period.timezone(),
            Timestamp.from(period.start()), Timestamp.from(period.end()), createdBy, Timestamp.from(now));
    }

    public void createDefaultQuota(String enterpriseId, Long initialLimit, Instant now) {
        if (quotaPolicySqlMapper.createDefaultQuota(UUID.randomUUID().toString(), enterpriseId, initialLimit, now) != 1) {
            throw new IllegalStateException("企业初始计数规则未保存");
        }
    }

    public void advancePermissionVersion(String enterpriseId) {
        statements.advancePermissionVersionEnterprise(enterpriseId);
    }

    public Optional<EnterpriseMemberResponse> findMember(String enterpriseId, String userId) {
        return identityQueryMapper.findMemberEnterpriseMember(enterpriseId, userId).stream()
            .map(rows -> mapMember(rows, enterpriseId)).findFirst();
    }

    public boolean activeMemberExists(String enterpriseId, String userId) {
        return DataAccessUtils.nullableSingleResult(
            identityQueryMapper.activeMemberExistsEnterpriseMember(enterpriseId, userId)) > 0;
    }

    public boolean updateMember(String enterpriseId, String userId, String name, String status, long revision,
                                Instant now) {
        return identityQueryMapper.updateMemberEnterpriseMember(name, status, Timestamp.from(now), enterpriseId, userId,
            revision) == 1;
    }

    public Optional<EnterpriseTeamResponse> findTeam(String enterpriseId, String teamId) {
        return enterpriseTeamTableMapper.findTeamEnterpriseTeam(enterpriseId, teamId).stream()
            .map(rows -> mapTeam(rows, enterpriseId)).findFirst();
    }

    public void insertTeam(String teamId, String enterpriseId, String name, String description, String status,
                           String ownerUserId, Instant now) {
        enterpriseTeamTableMapper.insertTeamEnterpriseTeam(teamId, enterpriseId, name, name.toLowerCase(Locale.ROOT),
            description, status, ownerUserId, Timestamp.from(now));
    }

    public boolean updateTeam(String teamId, String enterpriseId, String name, String description, String status,
                              String ownerUserId, long revision, Instant now) {
        return enterpriseTeamTableMapper.updateTeamEnterpriseTeam(name, name.toLowerCase(Locale.ROOT), description,
            status, ownerUserId, Timestamp.from(now), enterpriseId, teamId, revision) == 1;
    }

    public boolean advanceTeamRevision(String enterpriseId, String teamId, long revision, Instant now) {
        return enterpriseTeamTableMapper.advanceTeamRevisionEnterpriseTeam(Timestamp.from(now), enterpriseId, teamId,
            revision) == 1;
    }

    public int countOtherTeams(String enterpriseId, String userId, String excludedTeamId) {
        return DataAccessUtils.nullableSingleResult(
            memberTeamSqlMapper.countOtherTeamsEnterpriseTeamMember(enterpriseId, userId, excludedTeamId));
    }

    @Transactional
    public void replaceTeamMembers(String enterpriseId, String teamId, Set<String> userIds, Instant now) {
        Set<String> current = new HashSet<>(listTeamMembers(enterpriseId, teamId));
        for (String userId : current) {
            if (!userIds.contains(userId)) {
                memberTeamSqlMapper.replaceTeamMembersEnterpriseTeamMember(enterpriseId, teamId, userId);
                changedMemberTeams(enterpriseId, userId, now);
            }
        }
        for (String userId : userIds) {
            if (!current.contains(userId)) {
                memberTeamSqlMapper.addTeamMember(enterpriseId, teamId, userId, Timestamp.from(now));
                changedMemberTeams(enterpriseId, userId, now);
            }
        }
    }

    private void changedMemberTeams(String enterpriseId, String userId, Instant now) {
        identityQueryMapper.changedMemberTeamsEnterpriseMember(Timestamp.from(now), enterpriseId, userId);
    }

    private EnterpriseMemberResponse mapMember(EnterpriseQueryRow rows, String enterpriseId) {
        String userId = rows.getUserId();
        var assignedRoles = memberRoleQueryMapper.roleDetails(enterpriseId, userId);
        List<String> roleIds = assignedRoles.stream().map(SysRoleRow::getId).sorted().toList();
        List<String> roles = assignedRoles.stream().map(SysRoleRow::getName).toList();
        List<String> teams = memberTeamSqlMapper.mapMemberEnterpriseTeamMember(enterpriseId, userId);
        return new EnterpriseMemberResponse(userId, rows.getUsername(), rows.getDisplayName(), rows.getStatus(),
            roleIds, roles, teams, rows.getRevision());
    }

    private EnterpriseTeamResponse mapTeam(EnterpriseQueryRow rows, String enterpriseId) {
        String id = rows.getId();
        return new EnterpriseTeamResponse(id, rows.getName(), rows.getDescription(), rows.getStatus(),
            listTeamMembers(enterpriseId, id), rows.getOwnerUserId(), rows.getRevision());
    }

    private List<String> listTeamMembers(String enterpriseId, String teamId) {
        return memberTeamSqlMapper.listTeamMembersEnterpriseTeamMember(enterpriseId, teamId);
    }

    private EnterpriseEntity mapEnterprise(EnterpriseQueryRow rows) {
        return new EnterpriseEntity(rows.getId(), rows.getName(), rows.getStatus(),
            rows.getCreatedAt().toInstant(), rows.getUpdatedAt().toInstant());
    }
}
