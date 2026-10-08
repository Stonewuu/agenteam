package com.stonewu.agenteam.model.edition.entity;

import lombok.Getter;
import lombok.Setter;

/** 在迁移之前一次读取部署标记、企业和管理员初始化记录的一致状态。 */
@Getter
@Setter
public class InstallationPreflightRow {
    private long recordCount;
    private String installationId;
    private String edition;
    private String initialEnterpriseId;
    private long enterpriseCount;
    private long administratorCount;
    private long userCount;
    private long initialEnterpriseCount;
}
