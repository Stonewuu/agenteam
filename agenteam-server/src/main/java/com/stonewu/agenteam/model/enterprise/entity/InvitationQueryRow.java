package com.stonewu.agenteam.model.enterprise.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * InvitationMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class InvitationQueryRow {
    private String id;
    private String enterpriseId;
    private String email;
    private String displayName;
    private String teamIdsJson;
    private String roleIdsJson;
    private String tokenHash;
    private String status;
    private String createdBy;
    private String acceptedUserId;
    private Timestamp expiresAt;
    private Long revision = 0L;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String deliveryStatus;
    private String inviterName;
}
