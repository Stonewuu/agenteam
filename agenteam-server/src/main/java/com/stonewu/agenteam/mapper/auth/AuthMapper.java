package com.stonewu.agenteam.mapper.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.auth.entity.SystemSuperAdminLockRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.model.user.entity.UserPreferenceRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * 用户账户、超级管理员锁和企业成员关系的数据访问。
 */
@Component
public class AuthMapper {

    private final AppUserTableMapper users;

    private final UserPreferenceMapper preferences;

    private final IdentityQueryMapper members;

    private final SystemSuperAdminLockMapper locks;

    private final AuthSqlMapper statements;

    public AuthMapper(AppUserTableMapper users, UserPreferenceMapper preferences, IdentityQueryMapper members,
                      SystemSuperAdminLockMapper locks, AuthSqlMapper statements) {
        this.users = users;
        this.preferences = preferences;
        this.members = members;
        this.locks = locks;
        this.statements = statements;
    }

    public boolean superAdminInitialized() {
        return statements.superAdminInitialized();
    }

    public Optional<UserEntity> findByUsername(String username) {
        return Optional.ofNullable(users.selectOne(Wrappers.<AppUserRow>lambdaQuery()
            .eq(AppUserRow::getUsernameNormalized, username.trim().toLowerCase(Locale.ROOT)))).map(this::mapUser);
    }

    public Optional<UserEntity> findByLoginIdentifier(String identifier) {
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("@") ? Optional.ofNullable(users.selectOne(
            Wrappers.<AppUserRow>lambdaQuery().eq(AppUserRow::getEmailNormalized, normalized)
                .isNotNull(AppUserRow::getEmailVerifiedAt))).map(this::mapUser) : findByUsername(normalized);
    }

    public Optional<UserEntity> findById(String userId) {
        return Optional.ofNullable(users.selectById(userId)).map(this::mapUser);
    }

    @Transactional
    public void insertUser(String userId, String username, String passwordHash, String displayName, boolean superAdmin,
                           Instant now) {
        Instant created = time(now);
        AppUserRow user = new AppUserRow();
        user.setId(userId);
        user.setUsername(username.trim());
        user.setUsernameNormalized(username.trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordHash);
        user.setDisplayName(displayName);
        user.setStatus("active");
        user.setIsSuperAdmin(superAdmin ? 1 : 0);
        user.setPasswordChangedAt(created);
        user.setCreatedAt(created);
        user.setUpdatedAt(created);
        users.insert(user);
        UserPreferenceRow preference = new UserPreferenceRow();
        preference.setUserId(userId);
        preference.setCreatedAt(created);
        preference.setUpdatedAt(created);
        preferences.insert(preference);
    }

    public Optional<UserEntity> findByEmail(String email) {
        return Optional.ofNullable(users.selectOne(Wrappers.<AppUserRow>lambdaQuery()
            .eq(AppUserRow::getEmailNormalized, email.trim().toLowerCase(Locale.ROOT)))).map(this::mapUser);
    }

    public Optional<UserEntity> findByIdForUpdate(String userId) {
        return Optional.ofNullable(statements.lockUser(userId, null)).map(this::mapUser);
    }

    public Optional<UserEntity> findByIdForUpdate(String userId, int timeoutSeconds) {
        return Optional.ofNullable(statements.lockUser(userId, timeoutSeconds)).map(this::mapUser);
    }

    public boolean updateProfile(String userId, String displayName, long revision, Instant now) {
        return statements.updateProfile(userId, displayName, revision, time(now)) == 1;
    }

    public boolean replacePassword(String userId, String expectedHash, long expectedSessionVersion, String newHash,
                                   Instant now) {
        return statements.replacePassword(userId, expectedHash, expectedSessionVersion, newHash, time(now)) == 1;
    }

    public void invalidateUnusedTokens(String userId, Instant now) {
        statements.invalidateUnusedTokens(userId, time(now));
    }

    public boolean updateVerifiedEmail(String userId, String email, Instant now) {
        return statements.updateVerifiedEmail(userId, email, email.toLowerCase(Locale.ROOT), time(now)) == 1;
    }

    public void insertSuperAdminLock(String userId, Instant now) {
        SystemSuperAdminLockRow row = new SystemSuperAdminLockRow();
        row.setId(1);
        row.setUserId(userId);
        row.setCreatedAt(time(now));
        locks.insert(row);
    }

    public void setInitialEmail(String userId, String email) {
        users.update(Wrappers.<AppUserRow>lambdaUpdate().eq(AppUserRow::getId, userId).isNull(AppUserRow::getEmail)
            .set(AppUserRow::getEmail, email).set(AppUserRow::getEmailNormalized, email.toLowerCase(Locale.ROOT)));
    }

    public boolean initializedByAnother(String candidateUserId) {
        return statements.initializedByAnother(candidateUserId);
    }

    public boolean isActiveMember(String userId, String enterpriseId) {
        return statements.activeMemberCount(userId, enterpriseId) > 0;
    }

    public void addMember(String enterpriseId, String userId, String displayName, Instant now) {
        EnterpriseMemberRow row = new EnterpriseMemberRow();
        row.setEnterpriseId(enterpriseId);
        row.setUserId(userId);
        row.setDisplayName(displayName);
        row.setStatus("active");
        row.setJoinedAt(time(now));
        row.setCreatedAt(time(now));
        row.setUpdatedAt(time(now));
        members.insert(row);
    }

    public void updateLastEnterprise(String userId, String enterpriseId, Instant now) {
        users.update(Wrappers.<AppUserRow>lambdaUpdate().eq(AppUserRow::getId, userId)
            .set(AppUserRow::getLastEnterpriseId, enterpriseId).set(AppUserRow::getUpdatedAt, time(now)));
    }

    private UserEntity mapUser(AppUserRow row) {
        return new UserEntity(row.getId(), row.getUsername(), row.getPasswordHash(), row.getDisplayName(),
            row.getStatus(), row.getIsSuperAdmin() != 0, row.getLastEnterpriseId(), row.getEmail(),
            row.getEmailVerifiedAt(), row.getSessionVersion(), row.getPasswordChangedAt(), row.getRevision(),
            row.getCreatedAt(), row.getUpdatedAt());
    }

    private Instant time(Instant value) {
        return value == null ? Instant.now() : value;
    }
}
