package com.stonewu.agenteam.mapper.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.permission.entity.SysRolePermissionRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * sys_role_permission 表的通用数据库操作。
 */
@Mapper
public interface SysRolePermissionTableMapper extends MPJBaseMapper<SysRolePermissionRow> {
    default int clearRolePermissions(String enterprise, String role) {
        return delete(
            new LambdaQueryWrapper<SysRolePermissionRow>().eq(SysRolePermissionRow::getEnterpriseId, enterprise)
                .eq(SysRolePermissionRow::getRoleId, role));
    }

    default List<String> mapRoleSysRolePermission(String enterpriseId, String roleId) {
        var criteria = new LambdaQueryWrapper<SysRolePermissionRow>().select(SysRolePermissionRow::getPermissionCode)
            .orderByAsc(SysRolePermissionRow::getPermissionCode).eq(SysRolePermissionRow::getEnterpriseId, enterpriseId)
            .eq(SysRolePermissionRow::getRoleId, roleId);
        return selectList(criteria).stream().map(storedRow -> storedRow.getPermissionCode()).toList();
    }
}
