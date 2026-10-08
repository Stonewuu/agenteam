package com.stonewu.agenteam.model.plugin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 plugin_tool 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("plugin_tool")
public class PluginToolRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "plugin_version_id")
    private String pluginVersionId;

    @TableField(value = "entry_id")
    private String entryId;

    @TableField(value = "source_json")
    private String sourceJson;

    @TableField(value = "name")
    private String name;

    @TableField(value = "description")
    private String description;

    @TableField(value = "schema_hash")
    private String schemaHash;

    @TableField(value = "input_schema_json")
    private String inputSchemaJson;

    @TableField(value = "output_schema_json")
    private String outputSchemaJson;

    @TableField(value = "annotations_json")
    private String annotationsJson;

    @TableField(value = "operation_class")
    private String operationClass;

    @TableField(value = "enabled")
    private Integer enabled;

    @TableField(value = "supports_deduplication")
    private Integer supportsDeduplication;

    @TableField(value = "supports_result_query")
    private Integer supportsResultQuery;

    @TableField(value = "supports_cancel")
    private Integer supportsCancel;

    @TableField(value = "redact_paths_json")
    private String redactPathsJson;

    @TableField(value = "timeout_seconds")
    private Integer timeoutSeconds;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
