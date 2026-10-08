package com.stonewu.agenteam.model.http.response;

import java.util.List;

/**
 * 列表只返回当前可见记录，不通过本页数量推断总数。
 */
public record PageResponse<T>(List<T> items, String nextCursor, boolean hasMore) {
}
