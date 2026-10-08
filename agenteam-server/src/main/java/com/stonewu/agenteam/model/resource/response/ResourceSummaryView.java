package com.stonewu.agenteam.model.resource.response;

import com.stonewu.agenteam.model.agent.response.AgentListingView;
import com.stonewu.agenteam.model.enterprise.response.ActorView;

import java.util.List;

/**
 * 维护列表的真实资源状态，公开员工介绍使用另一种响应。
 */
public record ResourceSummaryView(String id, String revision, String createdAt, String updatedAt, String kind,
                                  String name,
                                  String description, String icon, String color, ActorView owner, String subtype,
                                  String source,
                                  String status, VersionSummaryView publishedVersion, boolean hasUnpublishedChanges,
                                  List<TagView> tags, List<String> allowedActions, AgentListingView listing) {
}
