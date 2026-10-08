package com.stonewu.agenteam.model.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 对应 sys_permission 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("sys_permission")
public class SysPermissionRow {
    @TableId(value = "code", type = IdType.INPUT)
    private String code;

    @TableField(value = "name")
    private String name;

    @TableField(value = "scope")
    private String scope;

    @TableField(value = "menu_key")
    private String menuKey;

    @TableField(value = "menu_label")
    private String menuLabel;

    @TableField(value = "menu_path")
    private String menuPath;

    @TableField(value = "sort_no")
    private Integer sortNo;
}
