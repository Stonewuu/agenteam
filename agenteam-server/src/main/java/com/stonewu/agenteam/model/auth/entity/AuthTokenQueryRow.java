package com.stonewu.agenteam.model.auth.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * AuthTokenMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class AuthTokenQueryRow {
    private Integer amount = 0;
    private Timestamp oldest;
    private Timestamp consumedAt;
    private String id;
    private String userId;
    private String purpose;
    private String tokenHash;
    private String targetEmail;
    private Timestamp expiresAt;
    private Timestamp createdAt;
}
