package com.stonewu.agenteam.model.mail.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * MailDeliveryStateMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class MailDeliveryStateQueryRow {
    private String id;
    private String enterpriseId;
    private String createdBy;
    private String email;
    private String tokenHash;
    private String status;
    private Timestamp expiresAt;
    private String enterpriseStatus;
    private String memberStatus;
    private String userStatus;
    private String roleIdsJson;
    private String teamIdsJson;
}
