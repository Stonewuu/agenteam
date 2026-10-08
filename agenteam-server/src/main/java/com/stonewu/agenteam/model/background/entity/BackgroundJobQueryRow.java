package com.stonewu.agenteam.model.background.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * BackgroundJobMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class BackgroundJobQueryRow {
    private String id;
    private String enterpriseId;
    private String ownerUserId;
    private String kind;
    private String payloadJson;
    private Integer attemptCount = 0;
    private Integer maxAttempts = 0;
    private Long leaseVersion = 0L;
}
