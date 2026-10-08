package com.stonewu.agenteam.model.data.response;

import com.stonewu.agenteam.model.data.entity.DataField;

import java.util.List;
import java.util.Map;

/**
 * 预览只包含受限行数，行数统计来自完整 CSV 解析。
 */
public record DataImportPreviewView(String previewToken, List<DataField> fields, List<Map<String, Object>> rows,
                                    long rowCount, String expiresAt) {
}
