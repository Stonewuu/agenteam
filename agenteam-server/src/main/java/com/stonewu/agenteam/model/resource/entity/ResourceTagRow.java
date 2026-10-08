package com.stonewu.agenteam.model.resource.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 对应 resource_tag 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("resource_tag")
public class ResourceTagRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "resource_id")
    private String resourceId;

    @TableField(value = "tag_id")
    private String tagId;
}
