package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.plugin.response.BuiltinPluginView;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业配置只能选择实际存在的适配器，不能把名称当成可执行代码。
 */
@Component
public class BuiltinPluginRegistry {
    private final Map<String, BuiltinPluginAdapter> adapters = new LinkedHashMap<>();

    public BuiltinPluginRegistry(List<BuiltinPluginAdapter> registered) {
        for (var adapter : registered) {
            if (adapters.put(adapter.code(), adapter) != null) {
                throw new IllegalStateException(Messages.text("error.plugin.builtinPluginRegistry.init.1"));
            }
        }
    }

    public BuiltinPluginAdapter require(String code) {
        var adapter = adapters.get(code);
        if (adapter == null) {
            throw ApiException.invalidField("config.builtinCode", Messages.text("error.plugin.builtinPluginRegistry.require.1"));
        }
        return adapter;
    }

    public List<BuiltinPluginView> list() {
        return adapters.values().stream().filter(value -> !"group_collaboration".equals(value.code())).map(
            value -> new BuiltinPluginView(value.code(), value.name(), value.description(),
                value.tools().stream().map(tool -> tool.name()).toList(), BuiltinPluginPresentation.icon(value.code()),
                BuiltinPluginPresentation.color(value.code()))).toList();
    }
}
