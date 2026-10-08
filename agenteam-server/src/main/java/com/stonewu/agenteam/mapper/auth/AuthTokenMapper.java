package com.stonewu.agenteam.mapper.auth;

import com.stonewu.agenteam.model.auth.entity.AuthToken;
import com.stonewu.agenteam.model.auth.entity.AuthTokenQueryRow;
import com.stonewu.agenteam.model.auth.entity.IssuedToken;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 凭据领取、消费和邮件数量检查与账户锁处于同一业务事务。
 */
@Repository
public class AuthTokenMapper {

    private final AuthTokenSqlMapper statements;

    public AuthTokenMapper(AuthTokenSqlMapper statements) {
        this.statements = statements;
    }

    public void insert(IssuedToken token, String userId, String purpose, String email, Instant expiresAt, Instant now) {
        statements.insertAuthToken(token.id(), userId, purpose, token.hash(), email, Timestamp.from(expiresAt),
            Timestamp.from(now));
    }

    public Optional<AuthToken> findByHash(String hash) {
        return statements.findByHashAuthToken(hash).stream().map(this::map).findFirst();
    }

    public Optional<AuthToken> findLocked(String id) {
        return statements.findLockedAuthToken(id).stream().map(this::map).findFirst();
    }

    public Optional<AuthToken> findById(String id) {
        return statements.findByIdAuthToken(id).stream().map(this::map).findFirst();
    }

    public boolean consume(String id, Instant now) {
        return statements.consumeAuthToken(Timestamp.from(now), id) == 1;
    }

    public void cancelUnusedEmailVerifications(String userId, Instant now) {
        statements.cancelUnusedEmailVerificationsAuthToken(Timestamp.from(now), userId);
    }

    public long mailRetryAfter(String userId, String purpose, Instant now) {
        return DataAccessUtils.nullableSingleResult(
            statements.mailRetryAfterAuthToken(userId, purpose, Timestamp.from(now.minusSeconds(3600))).stream()
                .map(rows -> {
                    if (rows.getAmount() < 3) {
                        return 0L;
                    }
                    long remaining = rows.getOldest().toInstant().plusSeconds(3600).toEpochMilli() - now.toEpochMilli();
                    return Math.max((remaining + 999) / 1000, 1);
                }).toList());
    }

    private AuthToken map(AuthTokenQueryRow rows) {
        Timestamp consumed = rows.getConsumedAt();
        return new AuthToken(rows.getId(), rows.getUserId(), rows.getPurpose(), rows.getTokenHash(),
            rows.getTargetEmail(), rows.getExpiresAt().toInstant(), consumed == null ? null : consumed.toInstant(),
            rows.getCreatedAt().toInstant());
    }
}
