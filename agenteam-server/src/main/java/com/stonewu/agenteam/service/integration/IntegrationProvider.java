package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelApplicationCheck;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.model.integration.response.IntegrationProviderView;

import java.util.List;
import java.util.Map;

/**
 * 企业渠道应用的校验与凭证接口；身份授权和发送分别由独立能力接口提供。
 */
public interface IntegrationProvider {
    String code();

    String name();

    List<IntegrationProviderView.Field> publicFields();

    default void validateApplicationId(String value) {
    }

    default Map<String, Object> normalizeConfiguration(Map<String, Object> configuration) {
        if (configuration != null && !configuration.isEmpty()) {
            throw ApiException.invalidField("configuration", "此平台没有可设置的额外参数。");
        }
        return Map.of();
    }

    ChannelAccessToken fetchAccessToken(IntegrationApplication application, String secret);

    ChannelApplicationCheck checkApplication(IntegrationApplication application, ChannelAccessToken token);
}
