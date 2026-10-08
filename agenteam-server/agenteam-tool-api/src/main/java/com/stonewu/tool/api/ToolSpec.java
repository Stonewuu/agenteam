package com.stonewu.tool.api;

import java.util.List;
import java.util.Map;

/**
 * 工具公开定义；操作类别为 read（只读）、write（修改）、destructive（删除等高敏感操作）或 unknown（未确认类别）。
 */
public record ToolSpec(String name, String displayName, String description, String schemaHash,
                       Map<String, Object> inputSchema, Map<String, Object> outputSchema,
                       Map<String, Object> annotations, String operationClass, boolean supportsDeduplication,
                       boolean supportsResultQuery, boolean supportsCancel, List<String> redactPaths,
                       int timeoutSeconds) {

    public ToolSpec {
        redactPaths = List.copyOf(redactPaths);
    }
}
