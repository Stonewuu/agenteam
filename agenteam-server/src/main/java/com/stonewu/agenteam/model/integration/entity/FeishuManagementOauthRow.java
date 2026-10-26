package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 管理授权的一次性状态只保存摘要与加密校验材料。 */
@Getter
@Setter
@TableName("feishu_management_oauth")
public class FeishuManagementOauthRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String connectionId;
    private String userId;
    private Long userSessionVersion;
    private Long credentialRevision;
    private Boolean systemAccess;
    private String stateHash;
    private String nonceHash;
    private String encryptedJson;
    private String status;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
}
