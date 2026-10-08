package com.stonewu.agenteam.model.permission.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * ResourceAuthorizationMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ResourceAuthorizationQueryRow {
    private String subjectType;
    private String subjectId;
    private String capability;
    private String id;
    private String enterpriseId;
    private String kind;
    private String ownerUserId;
    private String status;
    private Long revision = 0L;
}
