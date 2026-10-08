package com.stonewu.agenteam.model.permission.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 对应 sys_role_permission 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("sys_role_permission")
public class SysRolePermissionRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "role_id")
    private String roleId;

    @TableField(value = "permission_code")
    private String permissionCode;
}
