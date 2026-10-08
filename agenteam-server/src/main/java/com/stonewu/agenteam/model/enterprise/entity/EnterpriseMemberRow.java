package com.stonewu.agenteam.model.enterprise.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 enterprise_member 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("enterprise_member")
public class EnterpriseMemberRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "display_name")
    private String displayName;

    @TableField(value = "status")
    private String status;

    @TableField(value = "joined_at")
    private Instant joinedAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;

    @TableField(value = "notification_sequence")
    private Long notificationSequence;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;
}
