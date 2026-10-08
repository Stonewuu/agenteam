package com.stonewu.agenteam.model.resource.entity;

/**
 * 已验证当前身份与可用状态的固定依赖。
 */
public record ResolvedDependency(ResourceRecord resource, ResourceVersionRecord version) {
}
