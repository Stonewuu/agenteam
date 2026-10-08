package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 message_feedback 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("message_feedback")
public class MessageFeedbackRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "message_id")
    private String messageId;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "`value`")
    private String value;

    @TableField(value = "comment")
    private String comment;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
