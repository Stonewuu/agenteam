package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseInvitationRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.InvitationQueryRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * InvitationMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface InvitationSqlMapper extends MPJBaseMapper<EnterpriseInvitationRow> {
    List<InvitationQueryRow> listInvitations(@Param("scope") OwnerQueryScope scope, @Param("query") String query,
                                             @Param("after") PagePosition after, @Param("limit") int limit);

    default List<InvitationQueryRow> findByHashEnterpriseInvitation(String hash) {
        var criteria = new LambdaQueryWrapper<EnterpriseInvitationRow>().eq(EnterpriseInvitationRow::getTokenHash,
            hash);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new InvitationQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getEmail() != null) {
                mappedRow.setEmail(storedRow.getEmail());
            }
            if (storedRow.getDisplayName() != null) {
                mappedRow.setDisplayName(storedRow.getDisplayName());
            }
            if (storedRow.getTeamIdsJson() != null) {
                mappedRow.setTeamIdsJson(storedRow.getTeamIdsJson());
            }
            if (storedRow.getRoleIdsJson() != null) {
                mappedRow.setRoleIdsJson(storedRow.getRoleIdsJson());
            }
            if (storedRow.getTokenHash() != null) {
                mappedRow.setTokenHash(storedRow.getTokenHash());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getDeliveryStatus() != null) {
                mappedRow.setDeliveryStatus(storedRow.getDeliveryStatus());
            }
            if (storedRow.getCreatedBy() != null) {
                mappedRow.setCreatedBy(storedRow.getCreatedBy());
            }
            if (storedRow.getAcceptedUserId() != null) {
                mappedRow.setAcceptedUserId(storedRow.getAcceptedUserId());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            if (storedRow.getUpdatedAt() != null) {
                mappedRow.setUpdatedAt(
                    (storedRow.getUpdatedAt() == null ? null : Timestamp.from(storedRow.getUpdatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<InvitationQueryRow> lockEnterpriseInvitation(@Param("enterpriseId") String enterpriseId,
                                                      @Param("id") String id);

    default List<InvitationQueryRow> viewEnterpriseInvitation(String enterpriseId, String id) {
        var criteria = JoinWrappers.lambda(EnterpriseInvitationRow.class).selectAll(EnterpriseInvitationRow.class)
            .selectAs(EnterpriseMemberRow::getDisplayName, InvitationQueryRow::getInviterName)
            .innerJoin(EnterpriseMemberRow.class,
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, EnterpriseInvitationRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, EnterpriseInvitationRow::getCreatedBy))
            .eq(EnterpriseInvitationRow::getEnterpriseId, enterpriseId).eq(EnterpriseInvitationRow::getId, id);
        return selectJoinList(InvitationQueryRow.class, criteria);
    }


    default int expirePendingEmailEnterpriseInvitation(Timestamp now, String enterpriseId, String emailNormalized) {
        return update(new LambdaUpdateWrapper<EnterpriseInvitationRow>().eq(EnterpriseInvitationRow::getEnterpriseId,
                enterpriseId).eq(EnterpriseInvitationRow::getEmailNormalized, emailNormalized)
            .eq(EnterpriseInvitationRow::getStatus, "pending").le(EnterpriseInvitationRow::getExpiresAt, now)
            .set(EnterpriseInvitationRow::getStatus, "expired").set(EnterpriseInvitationRow::getPendingEmail, null)
            .setIncrBy(EnterpriseInvitationRow::getRevision, 1).set(EnterpriseInvitationRow::getUpdatedAt, now));
    }

    default int insertEnterpriseInvitation(String id, String enterpriseId, String email, String value,
                                           String displayName, String teamIdsJson, String roleIdsJson, String note,
                                           String tokenHash, String createdBy, Timestamp expiresAt, Timestamp now) {
        var databaseRow = new EnterpriseInvitationRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setEmail(email);
        databaseRow.setEmailNormalized(value);
        databaseRow.setPendingEmail(value);
        databaseRow.setDisplayName(displayName);
        databaseRow.setTeamIdsJson(teamIdsJson);
        databaseRow.setRoleIdsJson(roleIdsJson);
        databaseRow.setNote(note);
        databaseRow.setTokenHash(tokenHash);
        databaseRow.setCreatedBy(createdBy);
        databaseRow.setExpiresAt((expiresAt == null ? null : expiresAt.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int acceptEnterpriseInvitation(String userId, Timestamp now, String enterpriseId, String id,
                                           long revision) {
        return update(new LambdaUpdateWrapper<EnterpriseInvitationRow>().eq(EnterpriseInvitationRow::getEnterpriseId,
                enterpriseId).eq(EnterpriseInvitationRow::getId, id).eq(EnterpriseInvitationRow::getStatus, "pending")
            .eq(EnterpriseInvitationRow::getRevision, revision).gt(EnterpriseInvitationRow::getExpiresAt, now)
            .set(EnterpriseInvitationRow::getStatus, "accepted").set(EnterpriseInvitationRow::getPendingEmail, null)
            .set(EnterpriseInvitationRow::getAcceptedUserId, userId).set(EnterpriseInvitationRow::getAcceptedAt, now)
            .setIncrBy(EnterpriseInvitationRow::getRevision, 1).set(EnterpriseInvitationRow::getUpdatedAt, now));
    }

    default int revokeEnterpriseInvitation(Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<EnterpriseInvitationRow>().eq(EnterpriseInvitationRow::getEnterpriseId,
                enterpriseId).eq(EnterpriseInvitationRow::getId, id).eq(EnterpriseInvitationRow::getRevision, revision)
            .eq(EnterpriseInvitationRow::getStatus, "pending").set(EnterpriseInvitationRow::getStatus, "revoked")
            .set(EnterpriseInvitationRow::getPendingEmail, null).setIncrBy(EnterpriseInvitationRow::getRevision, 1)
            .set(EnterpriseInvitationRow::getUpdatedAt, now));
    }

    default int resendEnterpriseInvitation(Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<EnterpriseInvitationRow>().eq(EnterpriseInvitationRow::getEnterpriseId,
                enterpriseId).eq(EnterpriseInvitationRow::getId, id).eq(EnterpriseInvitationRow::getRevision, revision)
            .eq(EnterpriseInvitationRow::getStatus, "pending")
            .set(EnterpriseInvitationRow::getDeliveryStatus, "pending")
            .set(EnterpriseInvitationRow::getDeliveryError, null).set(EnterpriseInvitationRow::getDeliveryAttempts, 0)
            .setIncrBy(EnterpriseInvitationRow::getRevision, 1).set(EnterpriseInvitationRow::getUpdatedAt, now));
    }
}
