package com.stonewu.agenteam.model.notification.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * NotificationDeliveryMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class NotificationDeliveryQueryRow {
    private String id;
    private String enterpriseId;
    private String ownerUserId;
}
