package com.stonewu.agenteam.model.plugin.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * PluginToolMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class PluginToolQueryRow {
    private String entryId;
    private String sourceJson;
    private String name;
    private String description;
    private String schemaHash;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String annotationsJson;
    private String operationClass;
    private Boolean supportsDeduplication = false;
    private Boolean supportsResultQuery = false;
    private Boolean supportsCancel = false;
    private String redactPathsJson;
    private Integer timeoutSeconds = 0;
    private String id;
    private String enterpriseId;
    private String pluginVersionId;
    private Boolean enabled = false;
}
