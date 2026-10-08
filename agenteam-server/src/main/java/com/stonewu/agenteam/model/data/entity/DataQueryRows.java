package com.stonewu.agenteam.model.data.entity;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 受限读取的实际行，以及是否还有未返回的内容。
 */
public record DataQueryRows(List<ObjectNode> rows, boolean hasMore, boolean truncated) {
}
