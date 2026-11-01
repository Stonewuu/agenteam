package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 企微创建阶段及加密材料。 */
@Getter
@Setter
@TableName("wecom_setup_job")
public class WecomSetupJobRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String ownerUserId;
    private Long ownerSessionVersion;
    private Boolean systemAccess;
    private String enterpriseConnectionId;
    private Long enterpriseCredentialRevision;
    private String connectionId;
    private String botId;
    private String inputJson;
    private String encryptedJson;
    private String platformName;
    private String stage;
    private String resumeStage;
    private Instant expiresAt;
    private Instant nextAttemptAt;
    private String errorCode;
    private String errorSummary;
    private String leaseOwner;
    private Instant leaseUntil;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
}
