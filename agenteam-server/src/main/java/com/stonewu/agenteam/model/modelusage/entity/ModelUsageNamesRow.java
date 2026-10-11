package com.stonewu.agenteam.model.modelusage.entity;

import lombok.Getter;
import lombok.Setter;

/** 本页调用关联的显示名称，不包含账号、邮箱或模型连接配置。 */
@Getter
@Setter
public class ModelUsageNamesRow {
    private String id;
    private String userName;
    private String modelProfileName;
}
