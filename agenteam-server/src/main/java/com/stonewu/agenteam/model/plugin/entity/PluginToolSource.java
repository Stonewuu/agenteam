package com.stonewu.agenteam.model.plugin.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 发布时保存的来源快照，旧工具记录通过集中解析入口补出来源。
 */
public record PluginToolSource(String type, String resourceId, String versionId, String implementationVersion,
                               String name, JsonNode config) {
}
