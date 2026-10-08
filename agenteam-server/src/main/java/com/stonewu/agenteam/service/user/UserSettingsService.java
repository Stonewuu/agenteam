package com.stonewu.agenteam.service.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.model.user.request.EmailChangeRequest;
import com.stonewu.agenteam.model.user.request.PasswordChangeRequest;
import com.stonewu.agenteam.model.user.request.PreferenceUpdateRequest;
import com.stonewu.agenteam.model.user.request.ProfileUpdateRequest;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.AuthSessionService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 个人设置修改的认证、重复请求和事务结果处理。
 */
@Service
public class UserSettingsService {
    private final AuthContextService context;
    private final UserProfileService profiles;
    private final IdempotentRequestService operations;
    private final AuthSessionService sessions;
    private final EmailChangeService email;

    public UserSettingsService(AuthContextService context, UserProfileService profiles,
                               IdempotentRequestService operations, AuthSessionService sessions,
                               EmailChangeService email) {
        this.context = context;
        this.profiles = profiles;
        this.operations = operations;
        this.sessions = sessions;
        this.email = email;
    }

    public ApiOperationResult profile(ProfileUpdateRequest payload, HttpServletRequest request) {
        var actor = context.requireUser(request.getSession(false));
        long revision = RequestPreconditions.revision(request);
        return operations.execute(request, actor, null, Set.of(), () -> context.requireUser(request.getSession(false)),
            () -> ApiOperationResult.of(200, profiles.updateProfile(actor, payload, revision)));
    }

    public ApiOperationResult preferences(PreferenceUpdateRequest payload, HttpServletRequest request) {
        var actor = context.requireUser(request.getSession(false));
        long revision = RequestPreconditions.revision(request);
        Set<String> provided = new HashSet<>();
        if (request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE) instanceof JsonNode body) {
            body.fieldNames().forEachRemaining(provided::add);
        }
        return operations.execute(request, actor, null, Set.of(), () -> context.requireUser(request.getSession(false)),
            () -> ApiOperationResult.of(200, profiles.updatePreferences(actor, payload, revision, provided)));
    }

    public ApiOperationResult password(PasswordChangeRequest payload, HttpServletRequest request) {
        var actor = context.requireUser(request.getSession(false));
        AtomicReference<UserEntity> verified = new AtomicReference<>();
        var result = operations.execute(request, actor, null, Set.of(),
            () -> context.requireUser(request.getSession(false)), () -> {
                verified.set(profiles.changePassword(actor, payload, request.getRemoteAddr()));
                return ApiOperationResult.of(200, Map.of("success", true));
            });
        if (!result.replayed()) {
            // 只能建立事务中已经核验的版本，不能在这里授予另一次密码修改产生的新版本。
            UserEntity changed = verified.get();
            sessions.establish(request, changed);
            context.requireUser(request.getSession(false));
        }
        return result;
    }

    public ApiOperationResult email(EmailChangeRequest payload, HttpServletRequest request) {
        var actor = context.requireUser(request.getSession(false));
        return operations.execute(request, actor, null, Set.of(), () -> context.requireUser(request.getSession(false)),
            () -> {
                email.request(actor, payload, request.getRemoteAddr());
                return ApiOperationResult.of(202, Map.of("success", true));
            });
    }
}
