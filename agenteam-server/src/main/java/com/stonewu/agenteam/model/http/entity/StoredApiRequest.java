package com.stonewu.agenteam.model.http.entity;

/**
 * 重复请求只保存摘要与安全业务结果，不保存原请求正文。
 */
public record StoredApiRequest(String id, String requestHash, String status, Integer httpStatus, String responseJson) {
}
