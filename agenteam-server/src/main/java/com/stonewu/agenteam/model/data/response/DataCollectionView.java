package com.stonewu.agenteam.model.data.response;

import com.stonewu.agenteam.model.data.entity.DataField;

import java.util.List;

/**
 * 集合详情只报告已保存的实际版本与行数，远程未知行数保留为空。
 */
public record DataCollectionView(String id, String revision, String createdAt, String updatedAt, String name,
                                 String sourceName, int activeGeneration, Long rowCount, String status,
                                 List<DataField> fields) {
}
