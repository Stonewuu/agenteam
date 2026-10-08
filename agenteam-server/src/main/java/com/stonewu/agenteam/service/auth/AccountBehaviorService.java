package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 只执行已经安装的账号扩展；公共业务先后执行的身份与权限检查保持独立。 */
@Service
public class AccountBehaviorService {
    private final List<AccountBehaviorExtension> extensions;

    public AccountBehaviorService(List<AccountBehaviorExtension> extensions) {
        this.extensions = List.copyOf(extensions);
    }

    public boolean allowed(UserEntity user) {
        return extensions.stream().allMatch(extension -> extension.allowed(user));
    }

    public boolean enterpriseAllowed(UserEntity user, String enterpriseId) {
        return extensions.stream().allMatch(extension -> extension.enterpriseAllowed(user, enterpriseId));
    }

    public boolean permissionAllowed(UserEntity user, String permission) {
        return extensions.stream().allMatch(extension -> extension.permissionAllowed(user, permission));
    }

    public DataScope permissionScope(UserEntity user, String enterprise, String permission, DataScope granted) {
        var current = granted;
        for (var extension : extensions) {
            current = Objects.requireNonNull(extension.permissionScope(user, enterprise, permission, current));
        }
        return current;
    }

    public ResourceQueryScope resourceScope(AuthContext actor, ResourceQueryScope granted) {
        var current = granted;
        for (var extension : extensions) {
            current = Objects.requireNonNull(extension.resourceScope(actor, current));
            if (!Objects.equals(granted.enterpriseId(), current.enterpriseId())
                || !Objects.equals(granted.userId(), current.userId()) || !Objects.equals(granted.kind(), current.kind())
                || !Objects.equals(granted.capability(), current.capability()) || granted.includeDeleted() != current.includeDeleted()) {
                throw new IllegalStateException("账号扩展不能改变资源查询的企业、成员、类型或操作");
            }
        }
        return current;
    }

    public boolean operationAllowed(String userId, AccountOperation operation) {
        return extensions.stream().allMatch(extension -> extension.operationAllowed(userId, operation));
    }

    public boolean membershipChangesAllowed(String enterpriseId) {
        return extensions.stream().allMatch(extension -> extension.membershipChangesAllowed(enterpriseId));
    }

    public void requireOperation(String userId, AccountOperation operation) {
        extensions.forEach(extension -> extension.requireOperation(userId, operation));
    }

    public void requireMembershipChange(String enterpriseId) {
        extensions.forEach(extension -> extension.requireMembershipChange(enterpriseId));
    }

    public Path workspaceRoot(Path root, String userId) {
        String partition = null;
        for (var extension : extensions) {
            var value = extension.workspacePartition(userId);
            if (value.isPresent()) {
                if (!value.get().matches("[a-z0-9][a-z0-9_-]{0,63}")
                    || (partition != null && !partition.equals(value.get()))) {
                    throw new IllegalStateException("账号工作文件目录名称无效或存在冲突");
                }
                partition = value.get();
            }
        }
        return partition == null ? root : root.resolve(partition);
    }
}
