package com.stonewu.agenteam.support;

import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;

/**
 * 各业务测试准备隔离模型时使用的数据，不属于生产导入接口。
 */
public record ModelProfileFixture(String enterpriseId, String actorUserId, String code, Integer versionNo,
                                  String name, String provider, String modelName, String baseUrl,
                                  String secretEnvironment, ModelCapabilities capabilities, Boolean enabled) {
}
