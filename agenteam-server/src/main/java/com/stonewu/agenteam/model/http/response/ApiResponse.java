package com.stonewu.agenteam.model.http.response;

/**
 * 新版接口的成功数据与本次请求信息。
 */
public record ApiResponse<T>(T data, Meta meta) {
    public record Meta(String requestId, String serverTime) {
    }
}
