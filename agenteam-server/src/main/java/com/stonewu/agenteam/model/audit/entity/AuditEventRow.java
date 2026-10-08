package com.stonewu.agenteam.model.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 audit_event 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("audit_event")
public class AuditEventRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "actor_user_id")
    private String actorUserId;

    @TableField(value = "actor_name")
    private String actorName;

    @TableField(value = "action")
    private String action;

    @TableField(value = "object_type")
    private String objectType;

    @TableField(value = "object_id")
    private String objectId;

    @TableField(value = "result")
    private String result;

    @TableField(value = "summary")
    private String summary;

    @TableField(value = "detail_redacted_json")
    private String detailRedactedJson;

    @TableField(value = "request_id")
    private String requestId;

    @TableField(value = "created_at")
    private Instant createdAt;
}
