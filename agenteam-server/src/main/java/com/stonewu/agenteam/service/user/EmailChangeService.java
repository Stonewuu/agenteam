package com.stonewu.agenteam.service.user;

import com.stonewu.agenteam.mapper.auth.AuthTokenMapper;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.model.user.request.EmailChangeRequest;
import com.stonewu.agenteam.service.auth.AccountInputValidation;
import com.stonewu.agenteam.service.auth.LoginRateLimitException;
import com.stonewu.agenteam.service.auth.SingleUseTokenGenerator;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.mail.MailQueueService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 验证当前密码后申请邮箱确认，不直接替换当前邮箱。
 */
@Service
public class EmailChangeService {
    private final AccountBehaviorService behavior;
    private final UserProfileService profiles;
    private final AuthTokenMapper tokens;
    private final SingleUseTokenGenerator generator;
    private final MailQueueService mail;
    private final Clock clock;

    public EmailChangeService(UserProfileService profiles, AuthTokenMapper tokens, SingleUseTokenGenerator generator,
                              MailQueueService mail, Clock clock, AccountBehaviorService behavior) {
        this.behavior = behavior;
        this.profiles = profiles;
        this.tokens = tokens;
        this.generator = generator;
        this.mail = mail;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void request(UserEntity actor, EmailChangeRequest request, String source) {
        if (request == null) {
            throw ApiException.invalidField("newEmail", "请输入新邮箱。");
        }
        String email = AccountInputValidation.email(request.newEmail(), "newEmail");
        behavior.requireOperation(actor.id(), AccountOperation.CHANGE_EMAIL);
        UserEntity user = profiles.verifyCurrentPassword(actor, request.currentPassword(), "email-change:" + source);
        if (user.emailVerifiedAt() != null && email.equalsIgnoreCase(user.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_VERIFIED", "这个邮箱已经完成验证，无需再次申请。");
        }
        Instant now = clock.instant();
        long wait = tokens.mailRetryAfter(user.id(), "email_verify", now);
        if (wait > 0) {
            throw new LoginRateLimitException(wait);
        }
        tokens.cancelUnusedEmailVerifications(user.id(), now);
        var token = generator.issue();
        Instant expires = now.plusSeconds(1800);
        tokens.insert(token, user.id(), "email_verify", email, expires, now);
        mail.enqueueAccountVerification(token, user.id(), email, expires);
    }
}
