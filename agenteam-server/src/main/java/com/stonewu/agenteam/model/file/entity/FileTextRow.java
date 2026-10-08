package com.stonewu.agenteam.model.file.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 file_text 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("file_text")
public class FileTextRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "file_id")
    private String fileId;

    @TableField(value = "sha256")
    private String sha256;

    @TableField(value = "content_text")
    private String contentText;

    @TableField(value = "truncated")
    private Integer truncated;

    @TableField(value = "created_at")
    private Instant createdAt;
}
