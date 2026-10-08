package com.stonewu.agenteam.model.enterprise.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 成员与账户关联后的公开字段。
 */
@Getter
@Setter
public class MemberDetailRow {
    private String userId;
    private String displayName;
    private String email;
    private String status;
    private Instant joinedAt;
    private Long revision;
}
