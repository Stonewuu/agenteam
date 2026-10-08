package com.stonewu.agenteam.model.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 data_collection 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("data_collection")
public class DataCollectionRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "resource_id")
    private String resourceId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "source_name")
    private String sourceName;

    @TableField(value = "active_generation")
    private Integer activeGeneration;

    @TableField(value = "file_id")
    private String fileId;

    @TableField(value = "row_count")
    private Long rowCount;

    @TableField(value = "status")
    private String status;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
