package com.stonewu.agenteam.model.http.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 api_request 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("api_request")
public class ApiRequestRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "scope_key")
    private String scopeKey;

    @TableField(value = "operation_key")
    private String operationKey;

    @TableField(value = "request_key")
    private String requestKey;

    @TableField(value = "request_hash")
    private String requestHash;

    @TableField(value = "status")
    private String status;

    @TableField(value = "http_status")
    private Integer httpStatus;

    @TableField(value = "response_json")
    private String responseJson;

    @TableField(value = "expires_at")
    private Instant expiresAt;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
