package com.stonewu.agenteam.mapper.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.enterprise.entity.MemberRelationRow;
import com.stonewu.agenteam.model.permission.entity.SysRolePermissionRow;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import com.stonewu.agenteam.model.permission.entity.SysUserRoleRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * 一次读取指定成员的全部未删除角色，关联时同时限制企业。
 */
@Mapper
public interface MemberRoleQueryMapper extends MPJBaseMapper<SysUserRoleRow> {
    default List<MemberRelationRow> forUsers(String enterprise, List<String> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        return selectJoinList(MemberRelationRow.class, JoinWrappers.lambda(SysUserRoleRow.class)
            .select(SysUserRoleRow::getUserId).selectAs(SysRoleRow::getId, MemberRelationRow::getId)
            .innerJoin(SysRoleRow.class, on -> on.eq(SysRoleRow::getEnterpriseId, SysUserRoleRow::getEnterpriseId)
                .eq(SysRoleRow::getId, SysUserRoleRow::getRoleId))
            .eq(SysUserRoleRow::getEnterpriseId, enterprise).in(SysUserRoleRow::getUserId, users)
            .isNull(SysRoleRow::getDeletedAt).orderByAsc(SysRoleRow::getId));
    }

    default List<String> loadSysUserRole(String enterprise, String user) {
        var criteria = new LambdaQueryWrapper<SysUserRoleRow>().select(SysUserRoleRow::getRoleId)
            .orderByAsc(SysUserRoleRow::getRoleId).eq(SysUserRoleRow::getEnterpriseId, enterprise)
            .eq(SysUserRoleRow::getUserId, user);
        return selectList(criteria).stream().map(storedRow -> storedRow.getRoleId()).toList();
    }

    default List<String> listUserRoleIdsSysUserRole(String enterpriseId, String userId) {
        var criteria = new LambdaQueryWrapper<SysUserRoleRow>().select(SysUserRoleRow::getRoleId)
            .orderByAsc(SysUserRoleRow::getRoleId).eq(SysUserRoleRow::getEnterpriseId, enterpriseId)
            .eq(SysUserRoleRow::getUserId, userId);
        return selectList(criteria).stream().map(storedRow -> storedRow.getRoleId()).toList();
    }

    default int replaceUserRolesSysUserRole(String enterpriseId, String userId, String roleId) {
        return delete(new LambdaQueryWrapper<SysUserRoleRow>().eq(SysUserRoleRow::getEnterpriseId, enterpriseId)
            .eq(SysUserRoleRow::getUserId, userId).eq(SysUserRoleRow::getRoleId, roleId));
    }

    default int addUserRole(String enterpriseId, String userId, String roleId, Timestamp now) {
        var databaseRow = new SysUserRoleRow();
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setUserId(userId);
        databaseRow.setRoleId(roleId);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }


    default List<String> listPermissionCodesSysUserRole(String userId, String enterpriseId) {
        var criteria = JoinWrappers.lambda(SysUserRoleRow.class).select(SysRolePermissionRow::getPermissionCode)
            .distinct().innerJoin(SysRoleRow.class,
                on -> on.eq(SysRoleRow::getEnterpriseId, SysUserRoleRow::getEnterpriseId)
                    .eq(SysRoleRow::getId, SysUserRoleRow::getRoleId)).innerJoin(EnterpriseMemberRow.class,
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, SysUserRoleRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, SysUserRoleRow::getUserId))
            .innerJoin(AppUserRow.class, on -> on.eq(AppUserRow::getId, EnterpriseMemberRow::getUserId))
            .innerJoin(EnterpriseRow.class, on -> on.eq(EnterpriseRow::getId, EnterpriseMemberRow::getEnterpriseId))
            .innerJoin(SysRolePermissionRow.class,
                on -> on.eq(SysRolePermissionRow::getEnterpriseId, SysRoleRow::getEnterpriseId)
                    .eq(SysRolePermissionRow::getRoleId, SysRoleRow::getId)).eq(SysUserRoleRow::getUserId, userId)
            .eq(SysUserRoleRow::getEnterpriseId, enterpriseId).eq(SysRoleRow::getStatus, "active")
            .isNull(SysRoleRow::getDeletedAt).eq(EnterpriseMemberRow::getStatus, "active")
            .eq(AppUserRow::getStatus, "active").eq(EnterpriseRow::getStatus, "active")
            .orderByAsc(SysRolePermissionRow::getPermissionCode);
        return selectJoinList(SysRolePermissionRow.class, criteria).stream()
            .map(storedRow -> storedRow.getPermissionCode()).toList();
    }

    default List<Integer> isEnterpriseAdminSysUserRole(String userId, String enterpriseId) {
        var criteria = JoinWrappers.lambda(SysUserRoleRow.class).innerJoin(SysRoleRow.class,
                on -> on.eq(SysRoleRow::getEnterpriseId, SysUserRoleRow::getEnterpriseId)
                    .eq(SysRoleRow::getId, SysUserRoleRow::getRoleId)).innerJoin(EnterpriseMemberRow.class,
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, SysUserRoleRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, SysUserRoleRow::getUserId))
            .innerJoin(AppUserRow.class, on -> on.eq(AppUserRow::getId, EnterpriseMemberRow::getUserId))
            .innerJoin(EnterpriseRow.class, on -> on.eq(EnterpriseRow::getId, EnterpriseMemberRow::getEnterpriseId))
            .eq(SysUserRoleRow::getUserId, userId).eq(SysUserRoleRow::getEnterpriseId, enterpriseId)
            .eq(SysRoleRow::getStatus, "active").isNull(SysRoleRow::getDeletedAt)
            .eq(EnterpriseMemberRow::getStatus, "active").eq(AppUserRow::getStatus, "active")
            .eq(EnterpriseRow::getStatus, "active").eq(SysRoleRow::getCode, "enterprise-admin")
            .eq(SysRoleRow::getBuiltin, true);
        return List.of(Math.toIntExact(selectJoinCount(criteria)));
    }

    default List<SysRoleRow> roleDetails(String enterprise, String user) {
        return selectJoinList(SysRoleRow.class, JoinWrappers.lambda(SysUserRoleRow.class)
            .select(SysRoleRow::getId, SysRoleRow::getName)
            .innerJoin(SysRoleRow.class, on -> on.eq(SysRoleRow::getEnterpriseId, SysUserRoleRow::getEnterpriseId)
                .eq(SysRoleRow::getId, SysUserRoleRow::getRoleId))
            .eq(SysUserRoleRow::getEnterpriseId, enterprise).eq(SysUserRoleRow::getUserId, user)
            .isNull(SysRoleRow::getDeletedAt).orderByAsc(SysRoleRow::getName, SysRoleRow::getId));
    }
}
