package com.stonewu.agenteam.model.file.entity;

import java.util.List;

/**
 * CSV 原始字段按列顺序保存，未加引号的空字段与空字符串分别保留。
 */
public record CsvRow(long row, List<String> values) {
}
