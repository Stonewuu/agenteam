package com.stonewu.agenteam.model.resource.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 对应 resource_dependency 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("resource_dependency")
public class ResourceDependencyRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "parent_version_id")
    private String parentVersionId;

    @TableField(value = "dependency_version_id")
    private String dependencyVersionId;

    @TableField(value = "binding_key")
    private String bindingKey;

    @TableField(value = "dependency_kind")
    private String dependencyKind;

    @TableField(value = "ordinal")
    private Integer ordinal;
}
