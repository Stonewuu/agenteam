package com.stonewu.agenteam.mapper.mail;

import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.mail.entity.QueuedMail;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * 邀请重发使用同一未过期链接，较早投递不能覆盖较新的发送状态。
 */
@Repository
public class MailQueueMapper {
    private final MailQueueSqlMapper statements;

    public MailQueueMapper(MailQueueSqlMapper statements) {
        this.statements = statements;
    }

    public static String invitationKey(String id, long revision) {
        return "mail:invitation:" + id + ":" + String.format(Locale.ROOT, "%019d", revision);
    }

    public Optional<QueuedMail> latestInvitation(String enterpriseId, String invitationId) {
        return statements.latestInvitationBackgroundJob(enterpriseId, "mail:invitation:" + invitationId + ":%").stream()
            .map(rows -> new QueuedMail(rows.getId(), rows.getPayloadJson())).findFirst();
    }

    public long invitationRetryAfter(String enterpriseId, String invitationId, Instant now) {
        return DataAccessUtils.nullableSingleResult(
            statements.invitationRetryAfterBackgroundJob(enterpriseId, "mail:invitation:" + invitationId + ":%",
                Timestamp.from(now.minusSeconds(3600))).stream().map(rows -> {

                if (rows.getAmount() < 3) {
                    return 0L;
                }

                long remaining = rows.getOldest().toInstant().plusSeconds(3600).toEpochMilli() - now.toEpochMilli();

                return Math.max((remaining + 999) / 1000, 1);

            }).toList());
    }

    public boolean replacePayload(JobLease lease, String ownerUserId, String payload, Instant now) {
        return statements.replacePayloadBackgroundJob(ownerUserId, payload, Timestamp.from(now), lease.id(),
            lease.leaseOwner(), lease.leaseVersion()) == 1;
    }
}
