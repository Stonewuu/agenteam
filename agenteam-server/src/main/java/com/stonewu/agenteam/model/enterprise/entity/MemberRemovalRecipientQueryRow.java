package com.stonewu.agenteam.model.enterprise.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * MemberRemovalRecipientMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class MemberRemovalRecipientQueryRow {
    private String userId;
    private String displayName;
    private Timestamp joinedAt;
}
