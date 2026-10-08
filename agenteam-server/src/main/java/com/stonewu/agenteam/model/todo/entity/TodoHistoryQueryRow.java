package com.stonewu.agenteam.model.todo.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * TodoHistoryMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class TodoHistoryQueryRow {
    private String id;
    private String actorUserId;
    private String actorName;
    private String action;
    private String beforeJson;
    private String afterJson;
    private Timestamp createdAt;
}
