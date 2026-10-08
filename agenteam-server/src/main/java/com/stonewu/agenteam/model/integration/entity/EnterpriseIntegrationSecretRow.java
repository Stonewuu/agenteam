package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * enterprise_integration_secret 的数据库记录，仅由渠道服务访问，不直接序列化为页面响应。
 */
@Getter
@Setter
@TableName("enterprise_integration_secret")
public class EnterpriseIntegrationSecretRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("connection_id")
    private String connectionId;

    @TableField("secret_name")
    private String secretName;

    @TableField("encrypted_value")
    private String encryptedValue;

    @TableField("revision")
    private Long revision;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
