package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 管理用户授权按企业、管理应用和用户隔离保存，不向页面暴露访问凭据。 */
@Getter
@Setter
@TableName("feishu_management_grant")
public class FeishuManagementGrantRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String connectionId;
    private String userId;
    private Long credentialRevision;
    private String status;
    private String encryptedJson;
    private String scopes;
    private Instant expiresAt;
    private Instant refreshExpiresAt;
    private Instant refreshLeaseUntil;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
}
