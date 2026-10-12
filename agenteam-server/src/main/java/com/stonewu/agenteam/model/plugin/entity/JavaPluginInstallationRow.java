package com.stonewu.agenteam.model.plugin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 安装版本只保留一个活动版本，失效记录保留说明但清除程序包。
 */
@Getter
@Setter
@TableName("java_plugin_installation")
public class JavaPluginInstallationRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String pluginId;
    private String pluginVersion;
    private String name;
    private String description;
    private String author;
    private String configurationSchema;
    private String toolsJson;
    private String artifactHash;
    @TableField(select = false)
    private byte[] artifact;
    private String status;
    private String activeKey;
    private String installedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
