package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 平台核验通过的机器人企业关联，与双方凭据版本绑定。 */
@Getter
@Setter
@TableName("wecom_bot_enterprise")
public class WecomBotEnterpriseRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String enterpriseConnectionId;
    private String externalTenantId;
    private Long botCredentialRevision;
    private Long enterpriseCredentialRevision;
    private String ownerSubjectHash;
    private String proofMethod;
    private Instant verifiedAt;
}
