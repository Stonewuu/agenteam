package com.stonewu.agenteam.mapper.auth;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamQueryMapper;
import com.stonewu.agenteam.mapper.permission.MemberRoleQueryMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse.EnterpriseChoice;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse.Preferences;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.enterprise.entity.MemberRelationRow;
import com.stonewu.agenteam.model.enterprise.response.MemberView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 只组装可公开的身份字段，关联记录按成员集合批量读取。
 */
@Repository
public class IdentityViewMapper {

    private final IdentityQueryMapper identities;

    private final EnterpriseTableMapper enterprises;

    private final UserPreferenceMapper preferences;

    private final MemberTeamQueryMapper teams;

    private final MemberRoleQueryMapper roles;

    public IdentityViewMapper(IdentityQueryMapper identities, EnterpriseTableMapper enterprises,
                              UserPreferenceMapper preferences, MemberTeamQueryMapper teams,
                              MemberRoleQueryMapper roles) {
        this.identities = identities;
        this.enterprises = enterprises;
        this.preferences = preferences;
        this.teams = teams;
        this.roles = roles;
    }

    public CurrentIdentityResponse current(UserEntity user, List<String> capabilities) {
        List<EnterpriseChoice> choices = enterprises(user.id());
        String selected = choices.stream().map(EnterpriseChoice::id).filter(id -> id.equals(user.lastEnterpriseId()))
            .findFirst().orElse(choices.isEmpty() ? null : choices.getFirst().id());
        var preference = preferences.find(user.id()).orElseThrow(() -> new EmptyResultDataAccessException(1));
        return new CurrentIdentityResponse(user.id(), user.username(), user.displayName(), user.email(),
            user.emailVerifiedAt() != null, user.superAdmin(), selected, choices,
            new Preferences(preference.theme(), preference.taskCompletionNotifications(), preference.memoryEnabled(),
                preference.responseLanguage(), Long.toString(preference.revision())), Long.toString(user.revision()), "password", null, capabilities);
    }

    public List<EnterpriseChoice> enterprises(String userId) {
        return identities.enterprises(userId).stream().map(this::choice).toList();
    }

    public Optional<EnterpriseChoice> enterprise(String enterpriseId) {
        return Optional.ofNullable(enterprises.selectById(enterpriseId)).map(this::choice);
    }

    public MemberView member(String enterpriseId, String userId) {
        return members(enterpriseId, List.of(userId)).stream().findFirst()
            .orElseThrow(() -> new EmptyResultDataAccessException(1));
    }

    public List<MemberView> members(String enterpriseId, List<String> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        var members = identities.members(enterpriseId, userIds).stream()
            .collect(Collectors.toMap(row -> row.getUserId(), Function.identity()));
        Map<String, List<String>> teamIds = group(teams.forUsers(enterpriseId, userIds));
        Map<String, List<String>> roleIds = group(roles.forUsers(enterpriseId, userIds));
        return userIds.stream().map(id -> {
            var row = members.get(id);
            if (row == null) {
                throw new EmptyResultDataAccessException(1);
            }
            return new MemberView(row.getUserId(), row.getDisplayName(), row.getEmail(), row.getStatus(),
                teamIds.getOrDefault(id, List.of()), roleIds.getOrDefault(id, List.of()), row.getJoinedAt().toString(),
                Long.toString(row.getRevision()));
        }).toList();
    }

    public String permissionVersion(String enterpriseId) {
        return Optional.ofNullable(enterprises.selectById(enterpriseId))
            .map(row -> Long.toString(row.getPermissionVersion()))
            .orElseThrow(() -> new EmptyResultDataAccessException(1));
    }

    private EnterpriseChoice choice(EnterpriseRow row) {
        return new EnterpriseChoice(row.getId(), row.getName(), row.getDescription(), row.getStatus(),
            row.getTimezone());
    }

    private Map<String, List<String>> group(List<MemberRelationRow> relations) {
        return relations.stream().collect(Collectors.groupingBy(MemberRelationRow::getUserId,
            Collectors.mapping(MemberRelationRow::getId, Collectors.toList())));
    }
}
