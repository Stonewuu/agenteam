package com.stonewu.agenteam.model.agent.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * AgentHireApplicationMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class AgentHireApplicationQueryRow {
    private String id;
    private String enterpriseId;
    private String userId;
    private String applicantName;
    private String agentId;
    private String agentName;
    private String agentConfigJson;
    private String status;
    private String requestNote;
    private String decisionNote;
    private Long revision = 0L;
    private Timestamp expiresAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
