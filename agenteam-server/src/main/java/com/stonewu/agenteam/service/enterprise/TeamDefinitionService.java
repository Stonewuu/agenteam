package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.service.i18n.Messages;

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
        authorization.changed(actor, "team.create", "team", id, Messages.text("text.enterprise.teamDefinitionService.create.1"),
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
        authorization.changed(actor, "team.update", "team", id, Messages.text("text.enterprise.teamDefinitionService.update.1"),
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
        authorization.changed(actor, "team.members.update", "team", id, Messages.text("text.enterprise.teamDefinitionService.members.1"),
            Map.of("before", before, "after", ids));
        return find(actor.enterpriseId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TeamView status(AuthContext actor, String id, String requested, long revision) {
        authorize(actor);
        var original = current(actor, id, revision);
        if (requested == null) {
            throw ApiException.invalidField("status", Messages.text("error.announcement.announcementStatusRequest.enabled.1"));
        }
        String status = EnterpriseValidation.status(requested, null);
        if (status.equals("active") && !teams.activeMemberExists(actor.enterpriseId(), original.owner().id())) {
            throw ApiException.invalidField("ownerUserId", Messages.text("error.enterprise.teamDefinitionService.status.1"));
        }
        if (!teams.updateTeam(id, actor.enterpriseId(), original.name(), original.description(), status,
            original.owner().id(), revision, clock.instant())) {
            throw ApiException.versionConflict(revision);
        }
        authorization.changed(actor, "team.status.update", "team", id, Messages.text("text.enterprise.teamDefinitionService.status.1"),
            Map.of("before", original.status(), "after", status));
        return find(actor.enterpriseId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AuthContext actor, String id, long revision) {
        authorize(actor);
        var original = current(actor, id, revision);
        dependencies.requireTeamUnused(actor.enterpriseId(), id);
        mutations.deleteTeam(actor.enterpriseId(), id, revision, clock.instant());
        authorization.changed(actor, "team.delete", "team", id, Messages.text("text.enterprise.teamDefinitionService.delete.1"), Map.of("name", original.name()));
    }

    private TeamWritePayload validate(AuthContext actor, String id, TeamWritePayload value) {
        if (value == null || value.name() == null || value.description() == null || value.ownerUserId() == null || value.memberIds() == null) {
            throw ApiException.invalidField("body", Messages.text("error.enterprise.teamDefinitionService.validate.1"));
        }
        String name = EnterpriseValidation.name(value.name(), Messages.text("label.team.name"), 50);
        String description = EnterpriseValidation.description(value.description());
        if (!teams.activeMemberExists(actor.enterpriseId(), value.ownerUserId())) {
            throw ApiException.invalidField("ownerUserId", Messages.text("error.enterprise.teamDefinitionService.validate.2"));
        }
        Set<String> members = validateMembers(actor.enterpriseId(), id, value.memberIds());
        return new TeamWritePayload(name, description, value.ownerUserId(), members.stream().sorted().toList());
    }

    private Set<String> validateMembers(String enterpriseId, String teamId, List<String> requested) {
        Set<String> members = EnterpriseValidation.identifiers(requested, 0, 500, Messages.text("label.team.members"));
        Set<String> current = teams.findTeam(enterpriseId, teamId).map(team -> Set.copyOf(team.memberUserIds()))
            .orElse(Set.of());
        for (String userId : members) {
            if (!teams.activeMemberExists(enterpriseId, userId) && !(current.contains(userId)
                && teams.findMember(enterpriseId, userId).filter(member -> !member.status().equals("removed"))
                .isPresent())) {
                throw ApiException.invalidField("memberIds", Messages.text("error.enterprise.teamDefinitionService.validateMembers.1"));
            }
            if (teams.countOtherTeams(enterpriseId, userId, teamId) >= 10) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMBER_TEAM_LIMIT",
                    Messages.text("error.enterprise.teamDefinitionService.validateMembers.2"));
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", Messages.text("error.enterprise.teamDefinitionService.find.1")));
    }

    private ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "TEAM_EXISTS", Messages.text("error.enterprise.teamDefinitionService.duplicate.1"));
    }
}
