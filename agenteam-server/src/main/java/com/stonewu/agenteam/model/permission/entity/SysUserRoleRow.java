package com.stonewu.agenteam.model.permission.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 sys_user_role 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("sys_user_role")
public class SysUserRoleRow {
    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "role_id")
    private String roleId;

    @TableField(value = "created_at")
    private Instant createdAt;
}
