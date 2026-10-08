package com.stonewu.agenteam.model.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 对应 data_field 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("data_field")
public class DataFieldRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "collection_id")
    private String collectionId;

    @TableField(value = "generation")
    private Integer generation;

    @TableField(value = "name")
    private String name;

    @TableField(value = "label")
    private String label;

    @TableField(value = "value_type")
    private String valueType;

    @TableField(value = "readable")
    private Integer readable;

    @TableField(value = "filterable")
    private Integer filterable;

    @TableField(value = "sortable")
    private Integer sortable;

    @TableField(value = "`sensitive`")
    private Integer sensitive;

    @TableField(value = "nullable")
    private Integer nullable;

    @TableField(value = "ordinal")
    private Integer ordinal;
}
