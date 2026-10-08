package com.stonewu.agenteam.model.export.entity;

import java.time.Instant;
import java.util.List;

/**
 * 同一次数据库读取的固定内容，文件写入发生在该读取事务结束之后。
 */
public record ExportSnapshot(ExportDefinition definition, List<byte[]> chunks, List<String> actorIds, int rowCount,
                             Instant readAt, String fileName) {
    @Override
    public String toString() {
        return "ExportSnapshot[rowCount=" + rowCount + ", readAt=" + readAt + "]";
    }
}
