package com.stonewu.agenteam.model.tool.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 已验证的工具定义；操作类别区分只读、修改、高敏感和未知，远程提示不作为可信分类。
 */
public record ToolDefinition(String name, String description, String schemaHash, JsonNode inputSchema,
                             JsonNode outputSchema, JsonNode annotations, String operationClass,
                             boolean supportsDeduplication, boolean supportsResultQuery, boolean supportsCancel,
                             List<String> redactPaths, int timeoutSeconds) {
}
