package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * enterprise_integration 的数据库记录，仅由渠道服务访问，不直接序列化为页面响应。
 */
@Getter
@Setter
@TableName("enterprise_integration")
public class EnterpriseIntegrationRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("provider_code")
    private String providerCode;

    @TableField("installation_type")
    private String installationType;

    @TableField("region_code")
    private String regionCode;

    @TableField("name")
    private String name;

    @TableField("external_tenant_id")
    private String externalTenantId;

    @TableField("external_app_id")
    private String externalAppId;

    @TableField("public_login_key")
    private String publicLoginKey;

    @TableField("status")
    private String status;

    @TableField("binding_enabled")
    private Boolean bindingEnabled;

    @TableField("login_enabled")
    private Boolean loginEnabled;

    @TableField("messaging_enabled")
    private Boolean messagingEnabled;

    @TableField("config_schema_version")
    private Integer configSchemaVersion;

    @TableField("config_json")
    private String configJson;

    @TableField("credential_revision")
    private Long credentialRevision;

    @TableField("identity_verified_at")
    private Instant identityVerifiedAt;

    @TableField("last_checked_at")
    private Instant lastCheckedAt;

    @TableField("last_checked_revision")
    private Long lastCheckedRevision;

    @TableField("last_check_status")
    private String lastCheckStatus;

    @TableField("last_check_error_code")
    private String lastCheckErrorCode;

    @TableField("last_check_error_summary")
    private String lastCheckErrorSummary;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("revision")
    private Long revision;

    @TableField("deleted_at")
    private Instant deletedAt;

    @TableField("deleted_token")
    private String deletedToken;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
