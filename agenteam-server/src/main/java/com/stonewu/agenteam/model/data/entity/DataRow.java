package com.stonewu.agenteam.model.data.entity;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * 一行已经按该版本字段顺序完成类型检查的数据。
 */
public record DataRow(long row, ArrayNode values) {
}
