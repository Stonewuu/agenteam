package com.stonewu.agenteam.model.plugin.response;

import java.util.Map;

/**
 * 工具清单只返回公开结构与经过验证的能力。
 */
public record PluginToolView(String id, String name, String displayName, String description, String operationClass,
                             boolean enabled,
                             boolean supportsDeduplication, boolean supportsResultQuery,
                             Map<String, Object> inputSchema, Map<String, Object> outputSchema,
                             String entryId, String sourceId, String sourceVersionId, String sourceName) {
}
