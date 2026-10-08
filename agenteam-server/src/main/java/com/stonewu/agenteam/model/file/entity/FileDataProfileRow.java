package com.stonewu.agenteam.model.file.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 file_data_profile 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("file_data_profile")
public class FileDataProfileRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "file_id")
    private String fileId;

    @TableField(value = "sha256")
    private String sha256;

    @TableField(value = "columns_json")
    private String columnsJson;

    @TableField(value = "row_count")
    private Long rowCount;

    @TableField(value = "created_at")
    private Instant createdAt;
}
