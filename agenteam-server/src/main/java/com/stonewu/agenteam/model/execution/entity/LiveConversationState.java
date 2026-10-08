package com.stonewu.agenteam.model.execution.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.response.StreamCursor;

import java.util.Map;

/**
 * 同次 Redis 读取取得的累计内容、数据库覆盖范围和实时位置。
 */
public record LiveConversationState(StreamCursor cursor, long baseDatabaseVersion, long appliedDatabaseVersion,
                                    String activeRunId, Map<String, JsonNode> objects) {
    public boolean covers(long databaseVersion) {
        return baseDatabaseVersion <= databaseVersion && databaseVersion <= appliedDatabaseVersion;
    }
}
