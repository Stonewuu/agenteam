package com.stonewu.agenteam.mapper.enterprise;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.enterprise.entity.InvitationEntity;
import com.stonewu.agenteam.model.enterprise.entity.InvitationQueryRow;
import com.stonewu.agenteam.model.enterprise.response.ActorView;
import com.stonewu.agenteam.model.enterprise.response.InvitationView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 邀请查询先限制企业和创建人范围，再进行搜索、排序及分页。
 */
@Repository
public class InvitationMapper {
    private final InvitationSqlMapper statements;
    private final ObjectMapper json;

    public InvitationMapper(InvitationSqlMapper statements, ObjectMapper json) {
        this.statements = statements;
        this.json = json;
    }

    public Optional<InvitationEntity> findByHash(String hash) {
        return statements.findByHashEnterpriseInvitation(hash).stream().map(this::entity).findFirst();
    }

    public Optional<InvitationEntity> lock(String enterpriseId, String id) {
        return statements.lockEnterpriseInvitation(enterpriseId, id).stream().map(this::entity).findFirst();
    }

    public InvitationView view(String enterpriseId, String id, Instant now) {
        return DataAccessUtils.nullableSingleResult(
            statements.viewEnterpriseInvitation(enterpriseId, id).stream().map(rows -> view(rows, now)).toList());
    }

    public List<InvitationView> list(String enterpriseId, String userId, DataScope scope, String query,
                                     PagePosition after, int limit, Instant now) {
        return statements.listInvitations(new OwnerQueryScope(enterpriseId, userId, scope.code()), query, after, limit)
            .stream()
            .map(rows -> view(rows, now)).toList();
    }

    public void expirePendingEmail(String enterpriseId, String email, Instant now) {
        statements.expirePendingEmailEnterpriseInvitation(Timestamp.from(now), enterpriseId,
            email.toLowerCase(Locale.ROOT));
    }

    public void insert(InvitationEntity invitation, String note, Instant now) {
        String normalizedEmail = invitation.email() == null ? null : invitation.email().toLowerCase(Locale.ROOT);
        if (statements.insertEnterpriseInvitation(invitation.id(), invitation.enterpriseId(), invitation.email(),
            normalizedEmail,
            invitation.displayName(), array(invitation.teamIds()), array(invitation.roleIds()), note,
            invitation.tokenHash(),
            invitation.createdBy(), Timestamp.from(invitation.expiresAt()), Timestamp.from(now)) != 1) {
            throw new IllegalStateException("邀请未能保存");
        }
    }

    public void accept(InvitationEntity invitation, String userId, Instant now) {
        int changed = statements.acceptEnterpriseInvitation(userId, Timestamp.from(now), invitation.enterpriseId(),
            invitation.id(), invitation.revision());
        if (changed != 1) {
            throw new IllegalStateException("邀请已变化，接受操作未提交");
        }
    }

    public void revoke(InvitationEntity invitation, Instant now) {
        int changed = statements.revokeEnterpriseInvitation(Timestamp.from(now), invitation.enterpriseId(),
            invitation.id(), invitation.revision());
        if (changed != 1) {
            throw new IllegalStateException("邀请已变化，撤回操作未提交");
        }
    }

    public void resend(InvitationEntity invitation, Instant now) {
        int changed = statements.resendEnterpriseInvitation(Timestamp.from(now), invitation.enterpriseId(),
            invitation.id(), invitation.revision());
        if (changed != 1) {
            throw new IllegalStateException("邀请已变化，重发操作未提交");
        }
    }

    private InvitationEntity entity(InvitationQueryRow rows) {
        return new InvitationEntity(rows.getId(), rows.getEnterpriseId(), rows.getEmail(), rows.getDisplayName(),
            readArray(rows.getTeamIdsJson()), readArray(rows.getRoleIdsJson()), rows.getTokenHash(), rows.getStatus(),
            rows.getCreatedBy(), rows.getAcceptedUserId(), rows.getExpiresAt().toInstant(), rows.getRevision());
    }

    private InvitationView view(InvitationQueryRow rows, Instant now) {
        Instant expiresAt = rows.getExpiresAt().toInstant();
        String status = rows.getStatus();
        if (status.equals("pending") && !expiresAt.isAfter(now)) {
            status = "expired";
        }
        return new InvitationView(rows.getId(), Long.toString(rows.getRevision()),
            rows.getCreatedAt().toInstant().toString(),
            rows.getUpdatedAt().toInstant().toString(), rows.getEmail(), rows.getDisplayName(),
            readArray(rows.getTeamIdsJson()), readArray(rows.getRoleIdsJson()), status, rows.getDeliveryStatus(),
            expiresAt.toString(), new ActorView(rows.getCreatedBy(), rows.getInviterName()));
    }

    private List<String> readArray(String value) {
        try {
            return List.copyOf(json.readValue(value, new TypeReference<List<String>>() {
            }));
        } catch (Exception failure) {
            throw new IllegalStateException("邀请的角色或团队记录无法读取", failure);
        }
    }

    private String array(List<String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (Exception failure) {
            throw new IllegalArgumentException("邀请的角色或团队无法保存", failure);
        }
    }
}
