package com.stonewu.agenteam.model.notification.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 成员候选查询结果，仅含企业内显示名称和分页所需字段。 */
@Getter
@Setter
public class NotificationRecipientOptionRow {
    private String id;
    private String name;
    private Instant joinedAt;
}
