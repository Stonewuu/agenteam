package com.stonewu.agenteam.model.security.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 credential 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("credential")
public class CredentialRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "kind")
    private String kind;

    @TableField(value = "ciphertext")
    private byte[] ciphertext;

    @TableField(value = "nonce")
    private byte[] nonce;

    @TableField(value = "auth_tag")
    private byte[] authTag;

    @TableField(value = "key_version")
    private String keyVersion;

    @TableField(value = "status")
    private String status;

    @TableField(value = "updated_by")
    private String updatedBy;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
