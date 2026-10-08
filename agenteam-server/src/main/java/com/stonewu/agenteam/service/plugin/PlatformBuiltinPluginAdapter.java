package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;

/**
 * 登记平台实际能力，只有携带真实执行上下文的内部入口可以调用。
 */
public final class PlatformBuiltinPluginAdapter implements BuiltinPluginAdapter {
    private final String code;
    private final String name;
    private final String description;
    private final List<ToolDefinition> tools;
    private final ObjectProvider<PlatformBusinessTools> business;

    public PlatformBuiltinPluginAdapter(String code, String name, String description, List<ToolDefinition> tools,
                                        ObjectProvider<PlatformBusinessTools> business) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.tools = tools;
        this.business = business;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<ToolDefinition> tools() {
        return tools;
    }

    @Override
    public JsonNode call(String toolName, String operationId, JsonNode arguments, Duration timeout,
                         ToolCallControl control) {
        throw new IllegalStateException("平台业务工具必须由真实任务的执行入口调用");
    }

    public JsonNode call(BuiltinExecutionContext context, String name, JsonNode arguments) {
        if (tools.stream().noneMatch(tool -> tool.name().equals(name))) {
            throw new IllegalArgumentException("当前插件没有提供此业务工具");
        }
        return business.getObject().call(context, name, arguments);
    }
}
