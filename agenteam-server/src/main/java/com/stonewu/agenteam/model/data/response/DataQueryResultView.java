package com.stonewu.agenteam.model.data.response;

import com.stonewu.agenteam.model.data.entity.DataField;

import java.util.List;
import java.util.Map;

/**
 * 返回实际查询版本与耗时，结果是否截断由真实数量和大小决定。
 */
public record DataQueryResultView(String collectionId, int generation, List<DataField> fields,
                                  List<Map<String, Object>> rows, boolean hasMore, boolean truncated, long durationMs) {
}
