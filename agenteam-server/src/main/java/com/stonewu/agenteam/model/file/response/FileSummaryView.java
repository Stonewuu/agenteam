package com.stonewu.agenteam.model.file.response;

/**
 * 文件大小和状态来自保存与检查结果，不返回存储路径。
 */
public record FileSummaryView(String id, String name, String mediaType, long sizeBytes, String status, String errorCode,
                              String createdAt) {
}
