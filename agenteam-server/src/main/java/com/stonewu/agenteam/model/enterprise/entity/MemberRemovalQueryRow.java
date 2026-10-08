package com.stonewu.agenteam.model.enterprise.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * MemberRemovalMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class MemberRemovalQueryRow {
    private Long revision = 0L;
    private String memberStatus;
    private String accountStatus;
    private Long permissionVersion = 0L;
    private String id;
    private String kind;
    private String teamId;
    private String status;
    private Integer currentAttemptNo = 0;
    private Long leaseVersion = 0L;
}
