package com.stonewu.agenteam.model.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 data_record 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("data_record")
public class DataRecordRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "collection_id")
    private String collectionId;

    @TableField(value = "generation")
    private Integer generation;

    @TableField(value = "row_no")
    private Long rowNo;

    @TableField(value = "values_json")
    private String valuesJson;

    @TableField(value = "created_at")
    private Instant createdAt;
}
