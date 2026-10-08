package com.stonewu.agenteam.model.todo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 todo_history 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("todo_history")
public class TodoHistoryRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "todo_id")
    private String todoId;

    @TableField(value = "actor_user_id")
    private String actorUserId;

    @TableField(value = "action")
    private String action;

    @TableField(value = "before_json")
    private String beforeJson;

    @TableField(value = "after_json")
    private String afterJson;

    @TableField(value = "created_at")
    private Instant createdAt;
}
