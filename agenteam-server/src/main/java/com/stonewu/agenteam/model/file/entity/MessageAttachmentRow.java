package com.stonewu.agenteam.model.file.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 message_attachment 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("message_attachment")
public class MessageAttachmentRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "message_id")
    private String messageId;

    @TableField(value = "file_id")
    private String fileId;

    @TableField(value = "ordinal")
    private Integer ordinal;

    @TableField(value = "created_at")
    private Instant createdAt;
}
