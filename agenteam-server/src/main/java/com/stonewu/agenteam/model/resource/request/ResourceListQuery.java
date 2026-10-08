package com.stonewu.agenteam.model.resource.request;

import java.util.List;

/**
 * 资源列表的明确筛选与分页条件。
 */
public record ResourceListQuery(String kind, String query, String status, String source, String subtype,
                                List<String> tagIds, String sort, String cursor, Integer limit) {
}
