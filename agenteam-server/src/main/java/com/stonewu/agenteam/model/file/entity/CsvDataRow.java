package com.stonewu.agenteam.model.file.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 原始数据行与字段说明的数据库读取结果。
 */
@Getter
@Setter
public class CsvDataRow {
    private long rowNo;
    private String valuesJson;
    private String columnsJson;
    private long rowCount;
}
