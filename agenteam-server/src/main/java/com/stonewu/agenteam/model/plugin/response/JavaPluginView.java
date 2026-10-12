package com.stonewu.agenteam.model.plugin.response;

import java.util.List;
import java.util.Map;

/**
 * 程序包状态来自安装记录，响应不包含可执行文件和连接凭据。
 */
public record JavaPluginView(String id, String pluginId, String version, String name, String description,
                             String author, String status, Map<String, Object> configurationSchema, List<Tool> tools,
                             String currentInstallationId) {
    public record Tool(String name, String description, String operationClass, Map<String, Object> annotations,
                       Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
    }
}
