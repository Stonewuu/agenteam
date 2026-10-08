package com.stonewu.agenteam.model.file.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 对应 file_data_row 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("file_data_row")
public class FileDataRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "file_id")
    private String fileId;

    @TableField(value = "row_no")
    private Long rowNo;

    @TableField(value = "values_json")
    private String valuesJson;
}
