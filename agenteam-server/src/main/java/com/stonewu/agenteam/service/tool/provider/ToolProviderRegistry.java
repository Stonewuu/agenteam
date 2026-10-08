package com.stonewu.agenteam.service.tool.provider;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.tool.api.ToolProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅使用宿主实际登记的工具来源；安装扩展的生命周期在后续实现中接入。
 */
@Component
public class ToolProviderRegistry {
    private final Map<String, ToolProvider> providers;

    public ToolProviderRegistry(List<ToolProvider> providers) {
        var values = new LinkedHashMap<String, ToolProvider>();
        for (var provider : providers) {
            if (values.putIfAbsent(provider.type(), provider) != null) {
                throw new IllegalStateException("工具来源类型重复登记");
            }
        }
        this.providers = Map.copyOf(values);
    }

    public ToolProvider require(String type) {
        var provider = providers.get(type);
        if (provider == null) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_SOURCE_UNAVAILABLE",
                "本次工具来源已不可用，请联系维护者检查。");
        }
        return provider;
    }
}
