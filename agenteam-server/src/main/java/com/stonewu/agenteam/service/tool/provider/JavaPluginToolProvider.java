package com.stonewu.agenteam.service.tool.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.service.plugin.JavaPluginRegistry;
import com.stonewu.agenteam.service.plugin.javaworker.JavaPluginProcesses;
import com.stonewu.agenteam.service.plugin.javaworker.JavaPluginInvocationContext;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import org.springframework.beans.factory.ObjectProvider;
import com.stonewu.agenteam.service.security.HttpCredentialService;
import com.stonewu.tool.api.ToolContext;
import com.stonewu.tool.api.ToolProvider;
import com.stonewu.tool.api.ToolSource;
import com.stonewu.tool.api.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 已发布工具通过统一入口执行，Java 插件只在独立进程中接收本次配置和凭据。
 */
@Component
public class JavaPluginToolProvider implements ToolProvider {
    private final JavaPluginRegistry registry;
    private final JavaPluginProcesses processes;
    private final HttpCredentialService credentials;
    private final ResourceJson json;
    private final ObjectProvider<ToolCallTransactions> transactions;

    public JavaPluginToolProvider(JavaPluginRegistry registry, JavaPluginProcesses processes,
                                   HttpCredentialService credentials, ResourceJson json, ObjectProvider<ToolCallTransactions> transactions) {
        this.registry = registry;
        this.processes = processes;
        this.credentials = credentials;
        this.json = json;
        this.transactions = transactions;
    }

    @Override
    public String type() {
        return "java";
    }

    @Override
    public List<ToolSpec> tools(ToolSource source, ToolContext context) {
        var installation = registry.require(context.enterpriseId(), source.version());
        registry.matchPlugin(installation, source.id());
        return registry.tools(installation).stream().map(tool -> new ToolSpec(tool.name(),
            tool.annotations().path("title").asText(tool.name()), tool.description(), tool.schemaHash(),
            json.object(tool.inputSchema()), tool.outputSchema() == null ? null : json.object(tool.outputSchema()),
            json.object(tool.annotations()), tool.operationClass(), false, false, true,
            tool.redactPaths(), tool.timeoutSeconds())).toList();
    }

    @Override
    public Map<String, Object> invoke(ToolSource source, ToolSpec tool, Map<String, Object> arguments, ToolContext context) {
        var installation = registry.require(context.enterpriseId(), source.version());
        registry.matchPlugin(installation, source.id());
        var fixed = registry.tools(installation).stream().filter(value -> value.name().equals(tool.name())
            && value.schemaHash().equals(tool.schemaHash())).findFirst();
        if (fixed.isEmpty()) {
            throw new IllegalStateException("Java 插件工具与安装时的定义不一致");
        }
        ObjectNode request = json.tree(Map.of("mode", "invoke", "tool", tool.name(), "arguments", arguments,
            "configuration", source.configuration().getOrDefault("configuration", Map.of()))).deepCopy();
        var credentialId = (String) source.configuration().get("credentialId");
        request.set("credentialHeaders", json.tree(credentials.resolve(context.enterpriseId(), credentialId).headers()));
        request.put("enterpriseId", context.enterpriseId()).put("actorId", context.actorId())
            .put("runId", context.runId()).put("operationId", context.operationId());
        var invocation = new JavaPluginInvocationContext(context, () -> {
            if (context instanceof ToolProviderContext actual && actual.run() != null) {
                transactions.getObject().recheckPluginCall(actual.lease(), actual.binding(), actual.call().id());
            } else {
                context.beforeSend();
            }
        });
        return json.object(processes.call(source.version(), registry.artifact(context.enterpriseId(), source.version()),
            request, invocation, () -> registry.require(context.enterpriseId(), source.version())));
    }
}
