package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 飞书创建任务保存明确阶段与加密注册材料，接口不直接返回数据库实体。 */
@Getter
@Setter
@TableName("feishu_setup_job")
public class FeishuSetupJobRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String ownerUserId;
    private Long ownerSessionVersion;
    private Boolean systemAccess;
    private String kind;
    private String managerConnectionId;
    private String connectionId;
    private Long connectionRevision;
    private Long credentialRevision;
    private String botId;
    private String inputJson;
    private String encryptedJson;
    private String stage;
    private String resumeStage;
    private Instant registrationExpiresAt;
    private Integer pollInterval;
    private Instant nextAttemptAt;
    private String externalVersionId;
    private String externalVersion;
    private String errorCode;
    private String errorSummary;
    private String leaseOwner;
    private Instant leaseUntil;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
}
