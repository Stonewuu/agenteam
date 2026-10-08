package com.stonewu.agenteam.mapper.mail;

import com.stonewu.agenteam.model.mail.entity.InvitationMailTarget;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 邮件投递与邀请业务状态分别保存，更新前检查是否仍为最新一轮投递。
 */
@Repository
public class MailDeliveryStateMapper {
    private final MailDeliveryStateSqlMapper statements;
    private final MailQueueMapper mail;

    public MailDeliveryStateMapper(MailDeliveryStateSqlMapper statements, MailQueueMapper mail) {
        this.statements = statements;
        this.mail = mail;
    }

    public Optional<InvitationMailTarget> invitation(String enterpriseId, String id) {
        return statements.invitationEnterpriseInvitation(enterpriseId, id).stream().map(
                rows -> new InvitationMailTarget(rows.getId(), rows.getEnterpriseId(), rows.getCreatedBy(), rows.getEmail(),
                    rows.getTokenHash(), rows.getStatus(), rows.getExpiresAt().toInstant(), rows.getEnterpriseStatus(),
                    rows.getMemberStatus(), rows.getUserStatus(), rows.getRoleIdsJson(), rows.getTeamIdsJson()))
            .findFirst();
    }

    public void invitationAttempt(String enterpriseId, String invitationId, String jobId, Instant now) {
        if (!lockLatest(enterpriseId, invitationId, jobId)) {
            return;
        }
        statements.invitationAttemptEnterpriseInvitation(Timestamp.from(now), enterpriseId, invitationId);
    }

    public void invitationResult(String enterpriseId, String invitationId, String jobId, String status, String error,
                                 Instant now) {
        if (!lockLatest(enterpriseId, invitationId, jobId)) {
            return;
        }
        statements.invitationResultEnterpriseInvitation(status, error, Timestamp.from(now), enterpriseId, invitationId);
    }

    private boolean lockLatest(String enterpriseId, String invitationId, String jobId) {
        if (enterpriseId == null) {
            return false;
        }
        var rows = statements.lockLatestEnterpriseInvitation(enterpriseId, invitationId);
        return !rows.isEmpty() && mail.latestInvitation(enterpriseId, invitationId).map(job -> job.id().equals(jobId))
            .orElse(false);
    }
}
