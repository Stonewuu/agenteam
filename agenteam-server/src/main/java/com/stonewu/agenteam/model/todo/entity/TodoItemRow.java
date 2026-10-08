package com.stonewu.agenteam.model.todo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 对应 todo_item 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("todo_item")
public class TodoItemRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "title")
    private String title;

    @TableField(value = "description")
    private String description;

    @TableField(value = "created_by")
    private String createdBy;

    @TableField(value = "owner_user_id")
    private String ownerUserId;

    @TableField(value = "team_id")
    private String teamId;

    @TableField(value = "due_date")
    private LocalDate dueDate;

    @TableField(value = "priority")
    private String priority;

    @TableField(value = "status")
    private String status;

    @TableField(value = "source_type")
    private String sourceType;

    @TableField(value = "source_conversation_id")
    private String sourceConversationId;

    @TableField(value = "source_message_id")
    private String sourceMessageId;

    @TableField(value = "source_run_id")
    private String sourceRunId;

    @TableField(value = "completed_at")
    private Instant completedAt;

    @TableField(value = "deleted_at")
    private Instant deletedAt;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
