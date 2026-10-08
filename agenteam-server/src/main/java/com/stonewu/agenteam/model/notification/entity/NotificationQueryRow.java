package com.stonewu.agenteam.model.notification.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * NotificationMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class NotificationQueryRow {
    private String id;
    private Long sequenceNo = 0L;
    private String category;
    private String title;
    private String body;
    private String targetType;
    private String targetId;
    private Timestamp readAt;
    private Timestamp createdAt;
}
