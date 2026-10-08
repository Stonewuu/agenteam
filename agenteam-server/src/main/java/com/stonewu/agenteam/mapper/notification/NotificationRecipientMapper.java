package com.stonewu.agenteam.mapper.notification;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.query.LikePattern;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRecipientOptionRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import com.stonewu.agenteam.model.permission.entity.SysUserRoleRow;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 当前有效成员候选；测试消息还必须已绑定指定应用且允许接收。 */
@Repository
public class NotificationRecipientMapper {
    private final IdentityQueryMapper members;

    public NotificationRecipientMapper(IdentityQueryMapper members) {
        this.members = members;
    }

    public List<String> administrators(String enterprise) {
        var query = JoinWrappers.lambda(EnterpriseMemberRow.class).select(EnterpriseMemberRow::getUserId).distinct()
            .innerJoin(AppUserRow.class, AppUserRow::getId, EnterpriseMemberRow::getUserId)
            .innerJoin(SysUserRoleRow.class, on -> on.eq(SysUserRoleRow::getEnterpriseId, EnterpriseMemberRow::getEnterpriseId)
                .eq(SysUserRoleRow::getUserId, EnterpriseMemberRow::getUserId))
            .innerJoin(SysRoleRow.class, on -> on.eq(SysRoleRow::getEnterpriseId, SysUserRoleRow::getEnterpriseId)
                .eq(SysRoleRow::getId, SysUserRoleRow::getRoleId))
            .eq(EnterpriseMemberRow::getEnterpriseId, enterprise).eq(EnterpriseMemberRow::getStatus, "active")
            .eq(AppUserRow::getStatus, "active").eq(SysRoleRow::getCode, "enterprise-admin").eq(SysRoleRow::getBuiltin, true)
            .eq(SysRoleRow::getStatus, "active").isNull(SysRoleRow::getDeletedAt).orderByAsc(EnterpriseMemberRow::getUserId);
        return members.selectJoinList(EnterpriseMemberRow.class, query).stream().map(EnterpriseMemberRow::getUserId).toList();
    }

    public List<NotificationRecipientOptionRow> selected(String enterprise, List<String> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        var query = JoinWrappers.lambda(EnterpriseMemberRow.class)
            .selectAs(EnterpriseMemberRow::getUserId, NotificationRecipientOptionRow::getId)
            .selectAs(EnterpriseMemberRow::getDisplayName, NotificationRecipientOptionRow::getName)
            .selectAs(EnterpriseMemberRow::getJoinedAt, NotificationRecipientOptionRow::getJoinedAt)
            .innerJoin(AppUserRow.class, AppUserRow::getId, EnterpriseMemberRow::getUserId)
            .eq(EnterpriseMemberRow::getEnterpriseId, enterprise).in(EnterpriseMemberRow::getUserId, users)
            .eq(EnterpriseMemberRow::getStatus, "active").eq(AppUserRow::getStatus, "active");
        return members.selectJoinList(NotificationRecipientOptionRow.class, query);
    }

    public List<NotificationRecipientOptionRow> page(String enterprise, String connection, String user, String search,
                                                     PagePosition after, int limit) {
        var query = JoinWrappers.lambda(EnterpriseMemberRow.class)
            .selectAs(EnterpriseMemberRow::getUserId, NotificationRecipientOptionRow::getId)
            .selectAs(EnterpriseMemberRow::getDisplayName, NotificationRecipientOptionRow::getName)
            .selectAs(EnterpriseMemberRow::getJoinedAt, NotificationRecipientOptionRow::getJoinedAt)
            .innerJoin(AppUserRow.class, AppUserRow::getId, EnterpriseMemberRow::getUserId)
            .eq(EnterpriseMemberRow::getEnterpriseId, enterprise).eq(EnterpriseMemberRow::getStatus, "active")
            .eq(AppUserRow::getStatus, "active").eq(user != null, EnterpriseMemberRow::getUserId, user)
            .like(EnterpriseMemberRow::getDisplayName, LikePattern.escapeWildcards(search));
        if (connection != null) {
            query.innerJoin(UserChannelBindingRow.class, on -> on
                .eq(UserChannelBindingRow::getEnterpriseId, EnterpriseMemberRow::getEnterpriseId)
                .eq(UserChannelBindingRow::getUserId, EnterpriseMemberRow::getUserId))
                .eq(UserChannelBindingRow::getConnectionId, connection).eq(UserChannelBindingRow::getStatus, "active")
                .eq(UserChannelBindingRow::getReceiveEnabled, true);
        }
        if (after != null) {
            query.and(group -> group.lt(EnterpriseMemberRow::getJoinedAt, after.time()).or(equal -> equal
                .eq(EnterpriseMemberRow::getJoinedAt, after.time()).lt(EnterpriseMemberRow::getUserId, after.id())));
        }
        query.orderByDesc(EnterpriseMemberRow::getJoinedAt, EnterpriseMemberRow::getUserId);
        return members.selectJoinPage(new Page<NotificationRecipientOptionRow>(1, limit + 1L, false), NotificationRecipientOptionRow.class, query).getRecords();
    }
}
