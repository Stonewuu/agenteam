package com.stonewu.agenteam.model.resource.response;

import java.util.List;
import java.util.Map;

/**
 * 不可变发布正文与获准查看的依赖摘要。
 */
public record ResourceVersionView(VersionSummaryView version, Map<String, Object> config,
                                  List<VersionDependencyView> dependencies) {
}
