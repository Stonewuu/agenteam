package com.stonewu.agenteam.mapper.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import com.stonewu.agenteam.model.permission.entity.PermissionQueryRow;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * PermissionMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface PermissionSqlMapper extends MPJBaseMapper<SysRoleRow> {
    int countSharedTeamMembers(@Param("scope") OwnerQueryScope scope, @Param("ownerUserId") String ownerUserId);


    List<Integer> operationScopeSysUserRole(@Param("userId") String userId, @Param("enterpriseId") String enterpriseId,
                                            @Param("permission") String permission);


    List<Integer> activeAdministratorCountSysUserRole(@Param("enterpriseId") String enterpriseId);


    default List<PermissionQueryRow> findRoleSysRole(String enterpriseId, String roleId) {
        var criteria = new LambdaQueryWrapper<SysRoleRow>().eq(SysRoleRow::getEnterpriseId, enterpriseId)
            .eq(SysRoleRow::getId, roleId).isNull(SysRoleRow::getDeletedAt);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new PermissionQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getCode() != null) {
                mappedRow.setCode(storedRow.getCode());
            }
            if (storedRow.getName() != null) {
                mappedRow.setName(storedRow.getName());
            }
            if (storedRow.getBuiltin() != null) {
                mappedRow.setBuiltin((storedRow.getBuiltin() != null && storedRow.getBuiltin() != 0));
            }
            if (storedRow.getDescription() != null) {
                mappedRow.setDescription(storedRow.getDescription());
            }
            if (storedRow.getDataScope() != null) {
                mappedRow.setDataScope(storedRow.getDataScope());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }

    default List<String> builtinRoleIdSysRole(String enterpriseId, String code) {
        var criteria = new LambdaQueryWrapper<SysRoleRow>().select(SysRoleRow::getId)
            .eq(SysRoleRow::getEnterpriseId, enterpriseId).eq(SysRoleRow::getCode, code)
            .eq(SysRoleRow::getBuiltin, true).isNull(SysRoleRow::getDeletedAt);
        return selectList(criteria).stream().map(storedRow -> storedRow.getId()).toList();
    }


    default int insertRoleSysRole(String roleId, String enterpriseId, String code, String name, String nameKey,
                                  String description, String code2, boolean builtin, Timestamp now) {
        var databaseRow = new SysRoleRow();
        databaseRow.setId(roleId);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setCode(code);
        databaseRow.setName(name);
        databaseRow.setNameKey(nameKey);
        databaseRow.setDescription(description);
        databaseRow.setDataScope(code2);
        databaseRow.setBuiltin((builtin ? 1 : 0));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }


}
