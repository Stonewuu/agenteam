package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 消息附件的受控下载及本地文件关联；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("chat_message_resource")
public class ChatMessageResourceRow implements EnterpriseScopedRecord {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("message_id")
    private String messageId;

    @TableField("resource_ordinal")
    private Integer resourceOrdinal;

    @TableField("source_bot_id")
    private String sourceBotId;

    @TableField("platform_resource_key")
    private String platformResourceKey;

    @TableField("fetch_material_encrypted")
    private String fetchMaterialEncrypted;

    @TableField("file_id")
    private String fileId;

    @TableField("status")
    private String status;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("sha256")
    private String sha256;

    @TableField("error_code")
    private String errorCode;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("lease_version")
    private Long leaseVersion;
}
