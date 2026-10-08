package com.stonewu.agenteam.model.resource.response;

import com.stonewu.agenteam.model.enterprise.response.ActorView;

/**
 * 构建者可查看的固定发布版本摘要。
 */
public record VersionSummaryView(String id, int versionNo, String name, String releaseNote, String status,
                                 ActorView publishedBy, String publishedAt) {
}
