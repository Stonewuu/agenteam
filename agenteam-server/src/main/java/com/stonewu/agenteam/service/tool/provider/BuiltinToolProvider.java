package com.stonewu.agenteam.service.tool.provider;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolProviderMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.plugin.BuiltinPluginRegistry;
import com.stonewu.agenteam.service.plugin.BuiltinToolInvocation;
import com.stonewu.agenteam.service.plugin.PlatformBuiltinInvocation;
import com.stonewu.agenteam.service.plugin.PlatformBuiltinPluginAdapter;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import com.stonewu.tool.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内置工具通过公共来源接口执行，平台业务写入仍使用原来的完整事务。
 */
@Component
public class BuiltinToolProvider implements ToolProvider {
    private final BuiltinPluginRegistry builtins;
    private final BuiltinToolInvocation calls;
    private final PlatformBuiltinInvocation platform;
    private final ToolProviderMapper mapper;
    private final ResourceJson json;
    private final ToolSchemaValidation schemas;

    public BuiltinToolProvider(BuiltinPluginRegistry builtins, BuiltinToolInvocation calls,
                               PlatformBuiltinInvocation platform,
                               ToolProviderMapper mapper, ResourceJson json, ToolSchemaValidation schemas) {
        this.builtins = builtins;
        this.calls = calls;
        this.platform = platform;
        this.mapper = mapper;
        this.json = json;
        this.schemas = schemas;
    }

    @Override
    public String type() {
        return "builtin";
    }

    @Override
    public List<ToolSpec> tools(ToolSource source, ToolContext context) {
        context.cancellation().requireActive();
        var adapter = builtins.require((String) source.configuration().get("builtinCode"));
        if (source.implementationVersion() != null && !source.implementationVersion()
            .equals(adapter.implementationVersion())) {
            throw unavailable();
        }
        return adapter.tools().stream().map(tool -> mapper.spec(tool, source)).toList();
    }

    @Override
    public Map<String, Object> invoke(ToolSource source, ToolSpec tool, Map<String, Object> arguments,
                                      ToolContext context) {
        if (!(context instanceof ToolProviderContext execution) || execution.run() == null) {
            throw new IllegalArgumentException(Messages.text("error.tool.builtinToolProvider.invoke.1"));
        }
        if (tools(source, context).stream()
            .noneMatch(actual -> actual.name().equals(tool.name()) && actual.schemaHash().equals(tool.schemaHash()))) {
            throw unavailable();
        }
        var adapter = builtins.require((String) source.configuration().get("builtinCode"));
        var input = json.tree(arguments);
        if (adapter instanceof PlatformBuiltinPluginAdapter business) {
            if (!execution.binding().readOnly()) {
                var result = platform.write(business, execution.lease(), execution.binding(), execution.call(), input,
                    context.timeout(), execution.cancellation());
                execution.markPersisted();
                return json.object(result);
            }
            var result = platform.read(business, execution.run(), execution.call(), input, execution.cancellation());
            schemas.result(execution.binding().definition().outputSchema(), result);
            return json.object(result);
        }
        var response = calls.invoke(adapter, execution.binding(), execution.call(), execution.lease(), input,
            context.timeout(), execution.remaining(), execution.cancellation());
        if (!response.path("isError").asBoolean(false)) {
            schemas.result(execution.binding().definition().outputSchema(), response);
        }
        return json.object(response);
    }

    @Override
    public ToolQuery query(ToolSource source, ToolSpec tool, ToolContext context) {
        if (!tool.supportsResultQuery()) {
            return ToolQuery.unknown();
        }
        if (tools(source, context).stream().noneMatch(actual -> actual.name().equals(tool.name()) && actual.schemaHash()
            .equals(tool.schemaHash()) && actual.supportsResultQuery())) {
            return ToolQuery.unknown();
        }
        var adapter = builtins.require((String) source.configuration().get("builtinCode"));
        if (!(context instanceof ToolProviderContext execution)) {
            throw new IllegalArgumentException(Messages.text("error.tool.builtinToolProvider.query.1"));
        }
        var result = adapter.query(tool.name(), context.operationId(), context.timeout(), execution.cancellation());
        return new ToolQuery(ToolQuery.Status.valueOf(result.status().name()),
            result.result() == null ? null : json.object(result.result()));
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "PLUGIN_TOOL_CHANGED",
            Messages.text("error.tool.builtinToolProvider.unavailable.1"));
    }
}
