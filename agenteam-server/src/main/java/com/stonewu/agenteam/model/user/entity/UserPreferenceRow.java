package com.stonewu.agenteam.model.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 user_preference 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("user_preference")
public class UserPreferenceRow {
    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    @TableField(value = "theme")
    private String theme;

    @TableField(value = "task_completion_notifications")
    private Integer taskCompletionNotifications;

    @TableField(value = "memory_enabled")
    private Integer memoryEnabled;

    @TableField(value = "response_language")
    private String responseLanguage;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
