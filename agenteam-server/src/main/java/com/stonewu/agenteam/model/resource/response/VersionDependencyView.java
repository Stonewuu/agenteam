package com.stonewu.agenteam.model.resource.response;

/**
 * 调用者仍有权查看的依赖名称及固定版本。
 */
public record VersionDependencyView(String resourceId, String versionId, String kind, String name, String bindingKey) {
}
