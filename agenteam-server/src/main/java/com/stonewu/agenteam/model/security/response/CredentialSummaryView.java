package com.stonewu.agenteam.model.security.response;

/**
 * 管理页面仅取得名称、用途、状态及真实引用数量。
 */
public record CredentialSummaryView(String id, String revision, String createdAt, String updatedAt,
                                    String name, String kind, String status, long referenceCount) {
}
