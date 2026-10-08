package com.stonewu.agenteam.model.plugin.response;

import java.util.List;

/**
 * 工具选择目录只返回可用的固定版本和公开工具定义。
 */
public record ToolSourceView(String resourceId, String versionId, int versionNo, String name, String description,
                             String icon, String color, String type, String code, List<PluginToolView> tools) {
}
