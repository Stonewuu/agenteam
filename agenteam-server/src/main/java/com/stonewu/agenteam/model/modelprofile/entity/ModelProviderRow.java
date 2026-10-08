package com.stonewu.agenteam.model.modelprofile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 model_provider 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("model_provider")
public class ModelProviderRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "protocol")
    private String protocol;

    @TableField(value = "base_url")
    private String baseUrl;

    @TableField(value = "api_key")
    private String apiKey;

    @TableField(value = "enabled")
    private Integer enabled;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
