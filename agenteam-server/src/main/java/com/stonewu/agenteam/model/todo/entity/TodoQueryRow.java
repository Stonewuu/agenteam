package com.stonewu.agenteam.model.todo.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * TodoMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class TodoQueryRow {
    private String id;
    private String enterpriseId;
    private String title;
    private String description;
    private String createdBy;
    private String ownerUserId;
    private String teamId;
    private LocalDate dueDate;
    private String priority;
    private String status;
    private String sourceType;
    private String sourceConversationId;
    private String sourceMessageId;
    private String sourceRunId;
    private Long revision = 0L;
    private String creatorName;
    private String ownerName;
    private String teamName;
    private Timestamp completedAt;
    private Timestamp deletedAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
