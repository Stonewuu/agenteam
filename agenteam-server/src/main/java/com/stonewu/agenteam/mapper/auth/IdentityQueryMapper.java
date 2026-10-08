package com.stonewu.agenteam.mapper.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.enterprise.entity.*;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * 使用类型化关联读取账户所属企业和成员公开字段。
 */
@Mapper
public interface IdentityQueryMapper extends MPJBaseMapper<EnterpriseMemberRow> {

    default List<EnterpriseRow> enterprises(String userId) {
        return selectJoinList(EnterpriseRow.class, JoinWrappers.lambda(EnterpriseMemberRow.class)
            .select(EnterpriseRow::getId, EnterpriseRow::getName, EnterpriseRow::getDescription,
                EnterpriseRow::getStatus, EnterpriseRow::getTimezone)
            .innerJoin(EnterpriseRow.class, EnterpriseRow::getId, EnterpriseMemberRow::getEnterpriseId)
            .eq(EnterpriseMemberRow::getUserId, userId).eq(EnterpriseMemberRow::getStatus, "active")
            .eq(EnterpriseRow::getStatus, "active").orderByAsc(EnterpriseMemberRow::getJoinedAt)
            .orderByAsc(EnterpriseRow::getId));
    }

    default List<MemberDetailRow> members(String enterprise, List<String> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        return selectJoinList(MemberDetailRow.class, JoinWrappers.lambda(EnterpriseMemberRow.class)
            .select(EnterpriseMemberRow::getUserId, EnterpriseMemberRow::getDisplayName, EnterpriseMemberRow::getStatus,
                EnterpriseMemberRow::getJoinedAt, EnterpriseMemberRow::getRevision).select(AppUserRow::getEmail)
            .innerJoin(AppUserRow.class, AppUserRow::getId, EnterpriseMemberRow::getUserId)
            .eq(EnterpriseMemberRow::getEnterpriseId, enterprise).in(EnterpriseMemberRow::getUserId, users));
    }

    default List<String> appendEnterpriseMember(String enterpriseId, String userId) {
        var criteria = new LambdaQueryWrapper<EnterpriseMemberRow>().select(EnterpriseMemberRow::getDisplayName)
            .eq(EnterpriseMemberRow::getEnterpriseId, enterpriseId).eq(EnterpriseMemberRow::getUserId, userId);
        return selectList(criteria).stream().map(storedRow -> storedRow.getDisplayName()).toList();
    }

    default int updateMemberEnterpriseMember(String name, String status, Timestamp now, String enterpriseId,
                                             String userId, long revision) {
        return update(
            new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterpriseId)
                .eq(EnterpriseMemberRow::getUserId, userId).eq(EnterpriseMemberRow::getRevision, revision)
                .ne(EnterpriseMemberRow::getStatus, "removed").set(EnterpriseMemberRow::getDisplayName, name)
                .set(EnterpriseMemberRow::getStatus, status).setIncrBy(EnterpriseMemberRow::getRevision, 1)
                .set(EnterpriseMemberRow::getUpdatedAt, now));
    }

    default int changedMemberTeamsEnterpriseMember(Timestamp now, String enterpriseId, String userId) {
        return update(
            new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterpriseId)
                .eq(EnterpriseMemberRow::getUserId, userId).setIncrBy(EnterpriseMemberRow::getRevision, 1)
                .set(EnterpriseMemberRow::getUpdatedAt, now));
    }

    default int rejoinEnterpriseMember(String name, Timestamp now, String enterpriseId, String userId) {
        return update(
            new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterpriseId)
                .eq(EnterpriseMemberRow::getUserId, userId).eq(EnterpriseMemberRow::getStatus, "removed")
                .set(EnterpriseMemberRow::getStatus, "active").set(EnterpriseMemberRow::getDisplayName, name)
                .set(EnterpriseMemberRow::getJoinedAt, now).set(EnterpriseMemberRow::getUpdatedAt, now)
                .setIncrBy(EnterpriseMemberRow::getRevision, 1));
    }

    default int updateNotificationSequence(long sequence, String enterprise, String user) {
        return update(
            new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterprise)
                .eq(EnterpriseMemberRow::getUserId, user).set(EnterpriseMemberRow::getNotificationSequence, sequence));
    }

    default List<EnterpriseQueryRow> findMemberEnterpriseMember(String enterpriseId, String userId) {
        var criteria = JoinWrappers.lambda(EnterpriseMemberRow.class).select(EnterpriseMemberRow::getUserId)
            .select(AppUserRow::getUsername).select(EnterpriseMemberRow::getDisplayName)
            .select(EnterpriseMemberRow::getStatus).select(EnterpriseMemberRow::getRevision)
            .innerJoin(AppUserRow.class, on -> on.eq(AppUserRow::getId, EnterpriseMemberRow::getUserId))
            .eq(EnterpriseMemberRow::getEnterpriseId, enterpriseId).eq(EnterpriseMemberRow::getUserId, userId);
        return selectJoinList(EnterpriseQueryRow.class, criteria);
    }

    default List<Integer> activeMemberExistsEnterpriseMember(String enterpriseId, String userId) {
        var criteria = JoinWrappers.lambda(EnterpriseMemberRow.class)
            .innerJoin(AppUserRow.class, on -> on.eq(AppUserRow::getId, EnterpriseMemberRow::getUserId))
            .eq(EnterpriseMemberRow::getEnterpriseId, enterpriseId).eq(EnterpriseMemberRow::getUserId, userId)
            .eq(EnterpriseMemberRow::getStatus, "active").eq(AppUserRow::getStatus, "active");
        return List.of(Math.toIntExact(selectJoinCount(criteria)));
    }

    default List<MemberRemovalQueryRow> loadEnterpriseMember(String enterprise, String user) {
        var criteria = JoinWrappers.lambda(EnterpriseMemberRow.class).select(EnterpriseMemberRow::getRevision)
            .selectAs(EnterpriseMemberRow::getStatus, MemberRemovalQueryRow::getMemberStatus)
            .selectAs(AppUserRow::getStatus, MemberRemovalQueryRow::getAccountStatus)
            .select(EnterpriseRow::getPermissionVersion)
            .innerJoin(AppUserRow.class, on -> on.eq(AppUserRow::getId, EnterpriseMemberRow::getUserId))
            .innerJoin(EnterpriseRow.class, on -> on.eq(EnterpriseRow::getId, EnterpriseMemberRow::getEnterpriseId))
            .eq(EnterpriseMemberRow::getEnterpriseId, enterprise).eq(EnterpriseMemberRow::getUserId, user)
            .ne(EnterpriseMemberRow::getStatus, "removed");
        return selectJoinList(MemberRemovalQueryRow.class, criteria);
    }

    default List<String> activeMemberIdEnterpriseMember(String enterprise, String user) {
        var criteria = JoinWrappers.lambda(EnterpriseMemberRow.class).select(AppUserRow::getId)
            .innerJoin(AppUserRow.class, on -> on.eq(AppUserRow::getId, EnterpriseMemberRow::getUserId))
            .eq(EnterpriseMemberRow::getEnterpriseId, enterprise).eq(EnterpriseMemberRow::getUserId, user)
            .eq(EnterpriseMemberRow::getStatus, "active").eq(AppUserRow::getStatus, "active");
        return selectJoinList(AppUserRow.class, criteria).stream().map(storedRow -> storedRow.getId()).toList();
    }
}
