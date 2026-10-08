package com.stonewu.agenteam.model.enterprise.entity;

import com.stonewu.agenteam.model.resource.entity.ResourceKind;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 仅为移除复核保存必要的编号和版本，不包含会话、待办正文或凭据。
 */
public record MemberRemovalState(long memberRevision, String memberStatus, String accountStatus, long permissionVersion,
                                 List<String> roleIds, List<String> teamIds, List<ResourceItem> resources,
                                 List<VersionedItem> ownedTeams, List<TodoItem> openTodos, List<RunItem> activeRuns,
                                 List<VersionedItem> enabledSchedules, boolean lastAdministrator) {
    public record VersionedItem(String id, long revision) {
    }

    public record ResourceItem(String id, ResourceKind kind, long revision) {
    }

    public record TodoItem(String id, long revision, String teamId) {
    }

    public record RunItem(String id, String status, int attempt, long leaseVersion) {
    }

    public Set<ResourceKind> resourceKinds() {
        return resources.stream().map(ResourceItem::kind).collect(Collectors.toSet());
    }

    public boolean hasOwnership() {
        return !resources.isEmpty() || !ownedTeams.isEmpty();
    }
}
