package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.model.auth.request.ApiBootstrapRequest;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.enterprise.EnterpriseValidation;
import com.stonewu.agenteam.service.edition.InstallationService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 首次管理员与企业在同一事务建立，事务提交后才由认证服务建立登录会话。
 */
@Service
public class AuthBootstrapService {
    private final AuthMapper users;
    private final EnterpriseProvisioningService provisioning;
    private final SetupCredentialVerifier credential;
    private final AuthLoginRateLimiter limiter;
    private final PasswordEncoder encoder;
    private final AuditEventService audit;
    private final Clock clock;
    private final InstallationService installation;

    public AuthBootstrapService(AuthMapper users, EnterpriseProvisioningService provisioning,
                                SetupCredentialVerifier credential, AuthLoginRateLimiter limiter,
                                PasswordEncoder encoder, AuditEventService audit, Clock clock,
                                InstallationService installation) {
        this.users = users;
        this.provisioning = provisioning;
        this.credential = credential;
        this.limiter = limiter;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        this.installation = installation;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserEntity create(ApiBootstrapRequest request, String sourceAddress) {
        limiter.checkSource("bootstrap:" + sourceAddress);
        if (users.superAdminInitialized()) {
            throw alreadyInitialized();
        }
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请填写初始化信息。");
        }
        credential.verify(request.setupCredential());
        String username = IdentityValidation.username(request.username());
        String name = EnterpriseValidation.name(request.displayName(), "显示名", 50);
        IdentityValidation.newPassword(request.password());
        String enterpriseName = EnterpriseValidation.name(request.enterpriseName(), "企业名称", 80);
        String email = AccountInputValidation.email(request.email(), "email");
        String timezone = AccountInputValidation.timezone(request.timezone());
        String userId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        try {
            installation.lockBeforeBootstrap();
            users.insertUser(userId, username, encoder.encode(request.password()), name, true, now);
            users.setInitialEmail(userId, email);
            users.insertSuperAdminLock(userId, now);
            UserEntity user = users.findById(userId).orElseThrow();
            String enterpriseId = provisioning.createInitial(enterpriseName, "", email, timezone, user, now)
                .enterpriseId();
            users.updateLastEnterprise(userId, enterpriseId, now);
            audit.record(enterpriseId, user, "auth.bootstrap", "user", userId, "完成首次初始化",
                Map.of("username", username));
            return users.findById(userId).orElseThrow();
        } catch (DuplicateKeyException exception) {
            if (users.initializedByAnother(userId)) {
                throw alreadyInitialized();
            }
            throw new ApiException(HttpStatus.CONFLICT, "VALIDATION_FAILED", "用户名或邮箱已被使用，请修改后重试。");
        }
    }

    private ApiException alreadyInitialized() {
        return new ApiException(HttpStatus.CONFLICT, "SYSTEM_ALREADY_INITIALIZED", "系统已经初始化，请前往登录。");
    }
}
