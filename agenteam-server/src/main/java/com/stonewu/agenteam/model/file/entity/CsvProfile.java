package com.stonewu.agenteam.model.file.entity;

import java.util.List;

/**
 * 完整检查 CSV 后取得的字段及真实行数，预览不依靠局部样本猜测整体类型。
 */
public record CsvProfile(List<Column> columns, long rowCount) {
    public record Column(String name, String valueType, boolean nullable) {
    }
}
