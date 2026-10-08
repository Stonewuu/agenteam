package com.stonewu.agenteam.model.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 system_super_admin_lock 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("system_super_admin_lock")
public class SystemSuperAdminLockRow {
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "created_at")
    private Instant createdAt;
}
