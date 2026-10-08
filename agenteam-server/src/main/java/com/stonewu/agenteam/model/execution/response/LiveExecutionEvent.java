package com.stonewu.agenteam.model.execution.response;

import java.util.Map;

/**
 * 第二版实时事件；实时序号与数据库已保存版本分别传递。
 */
public record LiveExecutionEvent(int protocolVersion, String generation, String sequence, String databaseVersion,
                                 String eventId, String enterpriseId, String conversationId, String runId,
                                 String createdAt, String type, Map<String, Object> payload) {
}
