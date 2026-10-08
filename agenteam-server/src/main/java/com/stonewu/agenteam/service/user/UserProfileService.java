package com.stonewu.agenteam.service.user;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.service.auth.IdentityResponseService;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse.Preferences;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.model.user.entity.UserLanguage;
import com.stonewu.agenteam.model.user.entity.UserPreference;
import com.stonewu.agenteam.model.user.request.PasswordChangeRequest;
import com.stonewu.agenteam.model.user.request.PreferenceUpdateRequest;
import com.stonewu.agenteam.model.user.request.ProfileUpdateRequest;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AuthLoginRateLimiter;
import com.stonewu.agenteam.service.auth.IdentityValidation;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.enterprise.EnterpriseValidation;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 全局个人资料与密码操作，不改变任何企业内的显示名或角色。
 */
@Service
public class UserProfileService {
    private final AccountBehaviorService behavior;
    private final AuthMapper users;
    private final IdentityResponseService views;
    private final UserPreferenceMapper preferences;
    private final PasswordEncoder encoder;
    private final AuthLoginRateLimiter limiter;
    private final AuditEventService audit;
    private final Clock clock;

    public UserProfileService(AuthMapper users, IdentityResponseService views, UserPreferenceMapper preferences,
                              PasswordEncoder encoder,
                              AuthLoginRateLimiter limiter, AuditEventService audit, Clock clock, AccountBehaviorService behavior) {
        this.behavior = behavior;
        this.users = users;
        this.views = views;
        this.preferences = preferences;
        this.encoder = encoder;
        this.limiter = limiter;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CurrentIdentityResponse updateProfile(UserEntity actor, ProfileUpdateRequest request, long revision) {
        UserEntity user = lockUser(actor);
        if (request == null) {
            throw ApiException.invalidField("displayName", "请输入显示名。");
        }
        String name = EnterpriseValidation.name(request.displayName(), "显示名", 50);
        if (user.revision() != revision) {
            throw ApiException.versionConflict(user.revision());
        }
        if (!users.updateProfile(user.id(), name, revision, clock.instant())) {
            throw ApiException.versionConflict(user.revision());
        }
        audit.record(null, user, "user.profile.update", "user", user.id(), "修改个人显示名",
            Map.of("before", user.displayName(), "after", name));
        return views.current(users.findById(user.id()).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preferences updatePreferences(UserEntity actor, PreferenceUpdateRequest request, long revision,
                                         Set<String> provided) {
        UserEntity user = lockUser(actor);
        UserPreference previous = preferences.find(user.id()).orElseThrow();
        if (previous.revision() != revision) {
            throw ApiException.versionConflict(previous.revision());
        }
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请提供要修改的设置。");
        }
        if (provided.contains("theme") && request.theme() == null) {
            throw ApiException.invalidField("theme", "请选择显示主题。");
        }
        if (provided.contains("taskCompletionNotifications") && request.taskCompletionNotifications() == null) {
            throw ApiException.invalidField("taskCompletionNotifications", "请选择是否接收完成通知。");
        }
        if (provided.contains("memoryEnabled") && request.memoryEnabled() == null) {
            throw ApiException.invalidField("memoryEnabled", "请选择是否启用记忆。");
        }
        if (provided.contains("responseLanguage") && UserLanguage.find(request.responseLanguage()).isEmpty()) {
            throw ApiException.invalidField("responseLanguage", "请选择支持的回复语言。");
        }
        String theme = request.theme() == null ? previous.theme() : request.theme();
        if (!Set.of("system", "light", "dark").contains(theme)) {
            throw ApiException.invalidField("theme", "请选择浅色、深色或跟随系统。");
        }
        if (!provided.isEmpty()) {
            var updated = new UserPreference(user.id(), theme,
                request.taskCompletionNotifications() == null ? previous.taskCompletionNotifications() : request.taskCompletionNotifications(),
                request.memoryEnabled() == null ? previous.memoryEnabled() : request.memoryEnabled(),
                request.responseLanguage() == null ? previous.responseLanguage() : request.responseLanguage(),
                revision);
            if (!preferences.update(updated, clock.instant())) {
                throw ApiException.versionConflict(previous.revision());
            }
            audit.record(null, user, "user.preferences.update", "user", user.id(), "修改个人偏好",
                Map.of("fields", provided));
        }
        var saved = preferences.find(user.id()).orElseThrow();
        return new Preferences(saved.theme(), saved.taskCompletionNotifications(), saved.memoryEnabled(),
            saved.responseLanguage(), Long.toString(saved.revision()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserEntity changePassword(UserEntity actor, PasswordChangeRequest request, String source) {
        behavior.requireOperation(actor.id(), AccountOperation.CHANGE_PASSWORD);
        if (request == null) {
            throw ApiException.invalidField("currentPassword", "请输入当前密码。");
        }
        IdentityValidation.newPassword(request.newPassword());
        UserEntity user = verifyCurrentPassword(actor, request.currentPassword(), "password-change:" + source);
        Instant now = clock.instant();
        if (!users.replacePassword(user.id(), user.passwordHash(), user.sessionVersion(),
            encoder.encode(request.newPassword()), now)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "账户信息已变化，请重新登录后重试。");
        }
        users.invalidateUnusedTokens(user.id(), now);
        audit.record(null, user, "user.password.change", "user", user.id(), "修改密码并撤销旧登录会话", Map.of());
        return users.findById(user.id()).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserEntity verifyCurrentPassword(UserEntity actor, String password, String source) {
        if (password == null || password.isEmpty() || password.codePointCount(0, password.length()) > 256) {
            throw ApiException.invalidField("currentPassword", "请输入当前密码。");
        }
        limiter.checkSource(source);
        String account = "user:" + actor.id();
        limiter.checkAccount(account);
        UserEntity user = lockUser(actor);
        if (!encoder.matches(password, user.passwordHash())) {
            limiter.failed(account);
            throw ApiException.invalidField("currentPassword", "当前密码不正确。");
        }
        limiter.succeeded(account);
        return user;
    }

    private UserEntity lockUser(UserEntity actor) {
        UserEntity user = users.findByIdForUpdate(actor.id())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请重新登录。"));
        if (!"active".equals(user.status()) || user.sessionVersion() != actor.sessionVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "登录已失效，请重新登录。");
        }
        return user;
    }
}
