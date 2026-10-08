package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * ConversationMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ConversationQueryRow {
    private String status;
    private String activeRunId;
    private Long lastSequence = 0L;
    private String eventDeliveryMode = "database";
    private String id;
    private String enterpriseId;
    private String userId;
    private String agentId;
    private String agentVersionId;
    private String hireId;
    private String title;
    private String agentName;
    private String agentIcon;
    private String agentColor;
    private String mode;
    private String approvalPolicy;
    private String modelProfileId;
    private String reasoningEffort;
    private String projectId;
    private Boolean favorite = false;
    private Long revision = 0L;
    private String previewResourceId;
    private Timestamp deletedAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
