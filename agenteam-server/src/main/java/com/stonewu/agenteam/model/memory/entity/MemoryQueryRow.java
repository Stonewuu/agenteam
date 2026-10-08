package com.stonewu.agenteam.model.memory.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * MemoryMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class MemoryQueryRow {
    private String agentId;
    private String name;
    private String agentIcon;
    private String agentColor;
    private Long memoryCount = 0L;
    private String id;
    private String enterpriseId;
    private String userId;
    private String memoryKey;
    private String content;
    private String sourceMessageId;
    private Long revision = 0L;
    private Timestamp updatedAt;
    private Timestamp expiresAt;
    private Timestamp createdAt;
}
