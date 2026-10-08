package com.stonewu.agenteam.model.resource.response;

import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;

import java.util.List;
import java.util.Map;

/**
 * 结构已经校验的草稿内容和当前字段错误，不向无权者返回授权名单。
 */
public record ResourceDetailView(ResourceSummaryView resource, Map<String, Object> draft,
                                 List<ResourceGrantSpec> grants,
                                 Map<String, List<String>> fieldErrors, ConnectionCheckView connectionCheck) {
}
