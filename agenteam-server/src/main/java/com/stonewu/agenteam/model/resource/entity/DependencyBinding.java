package com.stonewu.agenteam.model.resource.entity;

/**
 * 固定依赖的实际配置位置与类型，版本编号不从资源名称推测。
 */
public record DependencyBinding(String versionId, String kind, String bindingKey, int ordinal) {
}
