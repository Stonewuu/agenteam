package com.stonewu.agenteam.mapper.mail;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseInvitationRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.mail.entity.MailDeliveryStateQueryRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * MailDeliveryStateMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface MailDeliveryStateSqlMapper extends MPJBaseMapper<EnterpriseInvitationRow> {
    default List<MailDeliveryStateQueryRow> invitationEnterpriseInvitation(String enterpriseId, String id) {
        var criteria = JoinWrappers.lambda(EnterpriseInvitationRow.class).selectAll(EnterpriseInvitationRow.class)
            .selectAs(EnterpriseRow::getStatus, MailDeliveryStateQueryRow::getEnterpriseStatus)
            .selectAs(EnterpriseMemberRow::getStatus, MailDeliveryStateQueryRow::getMemberStatus)
            .selectAs(AppUserRow::getStatus, MailDeliveryStateQueryRow::getUserStatus)
            .innerJoin(EnterpriseRow.class, on -> on.eq(EnterpriseRow::getId, EnterpriseInvitationRow::getEnterpriseId))
            .innerJoin(EnterpriseMemberRow.class,
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, EnterpriseInvitationRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, EnterpriseInvitationRow::getCreatedBy))
            .innerJoin(AppUserRow.class, on -> on.eq(AppUserRow::getId, EnterpriseInvitationRow::getCreatedBy))
            .eq(EnterpriseInvitationRow::getEnterpriseId, enterpriseId).eq(EnterpriseInvitationRow::getId, id);
        return selectJoinList(MailDeliveryStateQueryRow.class, criteria);
    }


    default int invitationAttemptEnterpriseInvitation(Timestamp now, String enterpriseId, String invitationId) {
        return update(new LambdaUpdateWrapper<EnterpriseInvitationRow>().eq(EnterpriseInvitationRow::getEnterpriseId,
                enterpriseId).eq(EnterpriseInvitationRow::getId, invitationId)
            .setIncrBy(EnterpriseInvitationRow::getDeliveryAttempts, 1)
            .set(EnterpriseInvitationRow::getUpdatedAt, now));
    }

    default int invitationResultEnterpriseInvitation(String status, String error, Timestamp now, String enterpriseId,
                                                     String invitationId) {
        return update(new LambdaUpdateWrapper<EnterpriseInvitationRow>().eq(EnterpriseInvitationRow::getEnterpriseId,
                enterpriseId).eq(EnterpriseInvitationRow::getId, invitationId)
            .set(EnterpriseInvitationRow::getDeliveryStatus, status)
            .set(EnterpriseInvitationRow::getDeliveryError, error).setIncrBy(EnterpriseInvitationRow::getRevision, 1)
            .set(EnterpriseInvitationRow::getUpdatedAt, now));
    }

    List<String> lockLatestEnterpriseInvitation(@Param("enterpriseId") String enterpriseId,
                                                @Param("invitationId") String invitationId);
}
