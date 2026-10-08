package com.stonewu.agenteam.model.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 data_generation 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("data_generation")
public class DataGenerationRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "collection_id")
    private String collectionId;

    @TableField(value = "generation")
    private Integer generation;

    @TableField(value = "source_hash")
    private String sourceHash;

    @TableField(value = "file_id")
    private String fileId;

    @TableField(value = "row_count")
    private Long rowCount;

    @TableField(value = "created_at")
    private Instant createdAt;
}
