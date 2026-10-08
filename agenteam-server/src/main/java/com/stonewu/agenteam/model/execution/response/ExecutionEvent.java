package com.stonewu.agenteam.model.execution.response;

import java.util.Map;

/**
 * 当前正式事件协议；所有大整数使用十进制字符串。
 */
public record ExecutionEvent(int protocolVersion, String eventId, String enterpriseId,
                             String conversationId, String runId, String sequence,
                             String createdAt, String type, Map<String, Object> payload) {
}
