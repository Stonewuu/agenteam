package com.stonewu.agenteam.model.modelprofile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 model_profile 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("model_profile")
public class ModelProfileRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "provider_id")
    private String providerId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "model_name")
    private String modelName;

    @TableField(value = "capabilities_json")
    private String capabilitiesJson;

    @TableField(value = "enabled")
    private Integer enabled;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
