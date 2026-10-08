package com.stonewu.agenteam.service.plugin;

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
                throw new IllegalStateException("内置插件代码重复登记");
            }
        }
    }

    public BuiltinPluginAdapter require(String code) {
        var adapter = adapters.get(code);
        if (adapter == null) {
            throw ApiException.invalidField("config.builtinCode", "请选择当前支持的内置插件。");
        }
        return adapter;
    }

    public List<BuiltinPluginView> list() {
        return adapters.values().stream().map(
            value -> new BuiltinPluginView(value.code(), value.name(), value.description(),
                value.tools().stream().map(tool -> tool.name()).toList())).toList();
    }
}
