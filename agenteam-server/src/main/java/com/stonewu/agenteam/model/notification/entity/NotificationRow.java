package com.stonewu.agenteam.model.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 notification 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("notification")
public class NotificationRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "user_id")
    private String userId;

    @TableField("source_occurrence_id")
    private String sourceOccurrenceId;

    @TableField("initiator_user_id")
    private String initiatorUserId;

    @TableField(value = "sequence_no")
    private Long sequenceNo;

    @TableField(value = "event_key")
    private String eventKey;

    @TableField(value = "category")
    private String category;

    @TableField(value = "title")
    private String title;

    @TableField(value = "body")
    private String body;

    @TableField(value = "target_type")
    private String targetType;

    @TableField(value = "target_id")
    private String targetId;

    @TableField(value = "read_at")
    private Instant readAt;

    @TableField(value = "created_at")
    private Instant createdAt;
}
