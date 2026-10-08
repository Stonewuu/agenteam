package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 不存在的导出类型无法创建、执行或下载；重复注册应直接阻止启动。 */
@Component
public class ExportTypeRegistry {
    private final Map<String, ExportTypeHandler> handlers;

    public ExportTypeRegistry(List<ExportTypeHandler> handlers) {
        var registered = new HashMap<String, ExportTypeHandler>();
        for (var handler : handlers) {
            String type = handler.type();
            if (type == null || !type.matches("[a-z_]{1,32}") || registered.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("导出类型为空、无效或重复注册");
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public ExportTypeHandler require(ExportDefinition definition) {
        var handler = definition == null || definition.type() == null ? null : handlers.get(definition.type());
        if (handler == null) {
            throw ExportAccessService.unavailable();
        }
        return handler;
    }
}
