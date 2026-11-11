package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只使用服务端实际注册的渠道；增加其他渠道不改变调度器和通知业务代码。
 */
@Component
public class IntegrationProviderRegistry {
    private final Map<String, IntegrationProvider> providers;

    public IntegrationProviderRegistry(List<IntegrationProvider> implementations) {
        var registered = new LinkedHashMap<String, IntegrationProvider>();
        for (IntegrationProvider provider : implementations) {
            if (registered.putIfAbsent(provider.code(), provider) != null) {
                throw new IllegalStateException(Messages.text("error.integration.integrationProviderRegistry.init.1", provider.code()));
            }
        }
        providers = Map.copyOf(registered);
    }

    public List<IntegrationProvider> providers() {
        return providers.values().stream().sorted((left, right) -> left.code().compareTo(right.code())).toList();
    }

    public IntegrationProvider require(String code) {
        var provider = code == null ? null : providers.get(code);
        if (provider == null) {
            throw ApiException.invalidField("providerCode", Messages.text("error.integration.integrationProviderRegistry.require.1"));
        }
        return provider;
    }

    public ChannelIdentityProvider identity(String code) {
        if (require(code) instanceof ChannelIdentityProvider identity) {
            return identity;
        }
        throw ApiException.invalidField("providerCode", Messages.text("error.integration.integrationProviderRegistry.identity.1"));
    }

    public ChannelMessageSender sender(String code) {
        if (require(code) instanceof ChannelMessageSender sender) {
            return sender;
        }
        throw ApiException.invalidField("providerCode", Messages.text("error.integration.integrationProviderRegistry.sender.1"));
    }
}
