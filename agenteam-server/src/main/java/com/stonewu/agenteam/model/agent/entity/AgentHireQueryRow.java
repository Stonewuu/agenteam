package com.stonewu.agenteam.model.agent.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * AgentHireMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class AgentHireQueryRow {
    private Timestamp lastUsedAt;
    private String id;
    private String enterpriseId;
    private String userId;
    private String agentId;
    private String status;
    private Long revision = 0L;
    private Timestamp hiredAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
