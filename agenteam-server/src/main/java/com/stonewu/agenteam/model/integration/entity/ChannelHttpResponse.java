package com.stonewu.agenteam.model.integration.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 平台网络响应，仅在服务内部使用，不直接返回给前端或写日志。
 */
public record ChannelHttpResponse(int status, JsonNode body, Long retryAfterSeconds, String traceId) {
    @Override
    public String toString() {
        return "ChannelHttpResponse[status=" + status + ", 响应正文已隐藏]";
    }
}
