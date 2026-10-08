package com.stonewu.agenteam.model.export.entity;

import java.util.List;

/**
 * 只保存查询条件和结果涉及的操作者编号，读取文件时重新检查这些人的当前范围。
 */
public record ExportPayload(ExportDefinition definition, List<String> actorIds, Integer rowCount, String snapshotAt,
                            String expiresAt) {
    public static ExportPayload requested(ExportDefinition definition) {
        return new ExportPayload(definition, List.of(), null, null, null);
    }
}
