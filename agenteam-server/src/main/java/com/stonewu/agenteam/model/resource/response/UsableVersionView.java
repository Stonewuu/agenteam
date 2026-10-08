package com.stonewu.agenteam.model.resource.response;

/**
 * 配置选择只返回使用授权允许看到的摘要，不返回配置正文。
 */
public record UsableVersionView(String resourceId, String versionId, int versionNo, String kind, String name,
                                String description, String icon, String color) {
}
