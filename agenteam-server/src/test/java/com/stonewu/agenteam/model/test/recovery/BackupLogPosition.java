package com.stonewu.agenteam.model.test.recovery;

/**
 * 数据库二进制日志实际返回的文件与字节位置。
 */
public record BackupLogPosition(String file, long offset) {
}
