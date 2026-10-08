package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.user.entity.UserEntity;

import java.util.Optional;

/** 特殊账号的附加约束；不能代替公共的会话、成员关系和已授予权限检查。 */
public interface AccountBehaviorExtension {
    default boolean allowed(UserEntity user) {
        return true;
    }

    default boolean enterpriseAllowed(UserEntity user, String enterpriseId) {
        return true;
    }

    default boolean permissionAllowed(UserEntity user, String permission) {
        return true;
    }

    default DataScope permissionScope(UserEntity user, String enterpriseId, String permission, DataScope granted) {
        return granted;
    }

    default ResourceQueryScope resourceScope(AuthContext actor, ResourceQueryScope granted) {
        return granted;
    }

    default boolean operationAllowed(String userId, AccountOperation operation) {
        return true;
    }

    default boolean membershipChangesAllowed(String enterpriseId) {
        return true;
    }

    default void requireOperation(String userId, AccountOperation operation) {
    }

    default void requireMembershipChange(String enterpriseId) {
    }

    default Optional<String> workspacePartition(String userId) {
        return Optional.empty();
    }
}
