package com.stonewu.agenteam.service.edition;

/** 仅约束发行版的企业数量和范围，不能替代会话、成员关系及业务权限检查。 */
public interface EnterpriseEditionPolicy {
    void requireAdditionalCreation();

    void requireEnterpriseScope(String enterpriseId);
}
