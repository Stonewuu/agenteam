package com.stonewu.agenteam.model.background.response;

/**
 * 文件过期由实际结果记录判断，内部邮件、清理和执行任务不使用此响应。
 */
public record JobView(String id, String kind, String status, String resultFileId, String errorSummary, String expiresAt,
                      String snapshotAt, Integer rowCount, String createdAt, String updatedAt) {
}
