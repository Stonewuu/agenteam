package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.OrganizationMutationMapper;
import com.stonewu.agenteam.mapper.enterprise.OrganizationViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.request.TeamWritePayload;
import com.stonewu.agenteam.model.enterprise.response.TeamView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.todo.TodoOrganizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 团队定义和成员完整替换在同一企业事务中提交，修改版本同时保护两种编辑入口。
 */
@Service
public class TeamDefinitionService {
    private final EnterpriseAuthorizationService authorization;
    private final EnterpriseMapper teams;
    private final OrganizationViewMapper views;
    private final OrganizationMutationMapper mutations;
    private final OrganizationDependencyService dependencies;
    private final Clock clock;
    private final TodoOrganizationService todos;

    public TeamDefinitionService(EnterpriseAuthorizationService authorization, EnterpriseMapper teams,
                                 OrganizationViewMapper views,
                                 OrganizationMutationMapper mutations, OrganizationDependencyService dependencies,
                                 Clock clock, TodoOrganizationService todos) {
        this.authorization = authorization;
        this.teams = teams;
        this.views = views;
        this.mutations = mutations;
        this.dependencies = dependencies;
        this.clock = clock;
        this.todos = todos;
    }

    public void authorize(AuthContext actor) {
        authorization.requireEnterpriseScope(authorization.lockAndRequire(actor, "enterprise.teams.manage"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TeamView create(AuthContext actor, TeamWritePayload payload) {
        authorize(actor);
        String id = UUID.randomUUID().toString();
        var value = validate(actor, id, payload);
        try {
            teams.insertTeam(id, actor.enterpriseId(), value.name(), value.description(), "active", value.ownerUserId(),
                clock.instant());
            teams.replaceTeamMembers(actor.enterpriseId(), id, Set.copyOf(value.memberIds()), clock.instant());
        } catch (DuplicateKeyException exception) {
            throw duplicate();
        }
        authorization.changed(actor, "team.create", "team", id, "创建团队",
            Map.of("name", value.name(), "ownerUserId", value.ownerUserId(), "memberIds", value.memberIds()));
        return find(actor.enterpriseId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TeamView update(AuthContext actor, String id, TeamWritePayload payload, long revision) {
        authorize(actor);
        var original = current(actor, id, revision);
        var value = validate(actor, id, payload);
        todos.requireTeamOwnersIncluded(actor.enterpriseId(), id, Set.copyOf(value.memberIds()));
        try {
            if (!teams.updateTeam(id, actor.enterpriseId(), value.name(), value.description(), original.status(),
                value.ownerUserId(), revision, clock.instant())) {
                throw ApiException.versionConflict(revision);
            }
            teams.replaceTeamMembers(actor.enterpriseId(), id, Set.copyOf(value.memberIds()), clock.instant());
        } catch (DuplicateKeyException exception) {
            throw duplicate();
        }
        authorization.changed(actor, "team.update", "team", id, "修改团队资料和成员",
            Map.of("beforeName", original.name(), "afterName", value.name(), "ownerUserId", value.ownerUserId(),
                "memberIds", value.memberIds()));
        return find(actor.enterpriseId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TeamView members(AuthContext actor, String id, List<String> ids, long revision) {
        authorize(actor);
        current(actor, id, revision);
        Set<String> members = validateMembers(actor.enterpriseId(), id, ids);
        todos.requireTeamOwnersIncluded(actor.enterpriseId(), id, members);
        var before = teams.findTeam(actor.enterpriseId(), id).orElseThrow().memberUserIds();
        if (!teams.advanceTeamRevision(actor.enterpriseId(), id, revision, clock.instant())) {
            throw ApiException.versionConflict(revision);
        }
        teams.replaceTeamMembers(actor.enterpriseId(), id, members, clock.instant());
        authorization.changed(actor, "team.members.update", "team", id, "修改团队成员",
            Map.of("before", before, "after", ids));
        return find(actor.enterpriseId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TeamView status(AuthContext actor, String id, String requested, long revision) {
        authorize(actor);
        var original = current(actor, id, revision);
        if (requested == null) {
            throw ApiException.invalidField("status", "请选择启用或停用。");
        }
        String status = EnterpriseValidation.status(requested, null);
        if (status.equals("active") && !teams.activeMemberExists(actor.enterpriseId(), original.owner().id())) {
            throw ApiException.invalidField("ownerUserId", "请先把团队负责人改为有效成员，再启用团队。");
        }
        if (!teams.updateTeam(id, actor.enterpriseId(), original.name(), original.description(), status,
            original.owner().id(), revision, clock.instant())) {
            throw ApiException.versionConflict(revision);
        }
        authorization.changed(actor, "team.status.update", "team", id, "修改团队状态",
            Map.of("before", original.status(), "after", status));
        return find(actor.enterpriseId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AuthContext actor, String id, long revision) {
        authorize(actor);
        var original = current(actor, id, revision);
        dependencies.requireTeamUnused(actor.enterpriseId(), id);
        mutations.deleteTeam(actor.enterpriseId(), id, revision, clock.instant());
        authorization.changed(actor, "team.delete", "team", id, "删除团队", Map.of("name", original.name()));
    }

    private TeamWritePayload validate(AuthContext actor, String id, TeamWritePayload value) {
        if (value == null || value.name() == null || value.description() == null || value.ownerUserId() == null || value.memberIds() == null) {
            throw ApiException.invalidField("body", "请填写团队名称、简介、负责人和成员。");
        }
        String name = EnterpriseValidation.name(value.name(), "团队名称", 50);
        String description = EnterpriseValidation.description(value.description());
        if (!teams.activeMemberExists(actor.enterpriseId(), value.ownerUserId())) {
            throw ApiException.invalidField("ownerUserId", "请选择本企业有效成员作为负责人。");
        }
        Set<String> members = validateMembers(actor.enterpriseId(), id, value.memberIds());
        return new TeamWritePayload(name, description, value.ownerUserId(), members.stream().sorted().toList());
    }

    private Set<String> validateMembers(String enterpriseId, String teamId, List<String> requested) {
        Set<String> members = EnterpriseValidation.identifiers(requested, 0, 500, "团队成员");
        Set<String> current = teams.findTeam(enterpriseId, teamId).map(team -> Set.copyOf(team.memberUserIds()))
            .orElse(Set.of());
        for (String userId : members) {
            if (!teams.activeMemberExists(enterpriseId, userId) && !(current.contains(userId)
                && teams.findMember(enterpriseId, userId).filter(member -> !member.status().equals("removed"))
                .isPresent())) {
                throw ApiException.invalidField("memberIds", "新增团队成员必须来自本企业并且当前有效。");
            }
            if (teams.countOtherTeams(enterpriseId, userId, teamId) >= 10) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMBER_TEAM_LIMIT",
                    "一名成员最多加入十个团队，请调整后重试。");
            }
        }
        return members;
    }

    private TeamView current(AuthContext actor, String id, long revision) {
        var team = find(actor.enterpriseId(), id);
        if (!team.revision().equals(Long.toString(revision))) {
            throw ApiException.versionConflict(Long.parseLong(team.revision()));
        }
        return team;
    }

    private TeamView find(String enterpriseId, String id) {
        return views.team(enterpriseId, id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "团队不存在或无法访问。"));
    }

    private ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "TEAM_EXISTS", "团队名称已存在，请调整后重试。");
    }
}
