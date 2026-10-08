package com.stonewu.agenteam.service.enterprise;

import java.util.Map;

/**
 * 后续业务模块实现此接口，在组织记录删除前提供本模块实际依赖数量。
 */
public interface OrganizationDependencies {
    Map<String, Long> role(String enterpriseId, String roleId);

    Map<String, Long> team(String enterpriseId, String teamId);
}
