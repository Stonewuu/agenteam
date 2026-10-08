package com.stonewu.agenteam.service.tool.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolDefinitionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolProviderMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.plugin.RemoteMcpSession;
import com.stonewu.agenteam.service.security.HttpCredentialService;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import com.stonewu.tool.api.ToolContext;
import com.stonewu.tool.api.ToolProvider;
import com.stonewu.tool.api.ToolSource;
import com.stonewu.tool.api.ToolSpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 远程工具与内置工具共用定义和执行接口，凭据只在宿主连接时解析。
 */
@Component
public class RemoteMcpToolProvider implements ToolProvider {
    private final RestrictedHttpClient http;
    private final ObjectMapper mapper;
    private final ResourceJson json;
    private final HttpCredentialService credentials;
    private final PluginToolDefinitionMapper definitions;
    private final ToolProviderMapper tools;
    private final ToolSchemaValidation schemas;

    public RemoteMcpToolProvider(RestrictedHttpClient http, ObjectMapper mapper, ResourceJson json,
                                 HttpCredentialService credentials,
                                 PluginToolDefinitionMapper definitions, ToolProviderMapper tools,
                                 ToolSchemaValidation schemas) {
        this.http = http;
        this.mapper = mapper;
        this.json = json;
        this.credentials = credentials;
        this.definitions = definitions;
        this.tools = tools;
        this.schemas = schemas;
    }

    @Override
    public String type() {
        return "mcp";
    }

    @Override
    public List<ToolSpec> tools(ToolSource source, ToolContext context) {
        var config = json.tree(source.configuration());
        var auth = credentials.resolve(context.enterpriseId(), config.path("credentialId").asText(null));
        try (var session = context.cancellation().track(
            new RemoteMcpSession(http, mapper, config.path("endpoint").asText(), config.path("transport").asText(),
                auth.headers(), context.timeout(), 4 * 1024 * 1024))) {
            var discovered = session.listTools();
            if (discovered.stream().anyMatch(tool -> containsSecret(tool, auth.secretValues()))) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_TOOL_STRUCTURE_INVALID",
                    "远程工具清单包含不应公开的认证内容，无法保存，请检查服务配置。");
            }
            return definitions.remote(discovered, config.path("timeoutSeconds").asInt(30)).stream()
                .map(tool -> tools.spec(tool, source)).toList();
        }
    }

    @Override
    public Map<String, Object> invoke(ToolSource source, ToolSpec tool, Map<String, Object> arguments,
                                      ToolContext context) {
        var config = json.tree(source.configuration());
        var auth = credentials.resolve(context.enterpriseId(), config.path("credentialId").asText(null));
        if (!(context.cancellation() instanceof ToolCallControl control)) {
            throw new IllegalArgumentException("远程工具缺少宿主取消范围");
        }
        try (var session = control.track(
            new RemoteMcpSession(http, mapper, config.path("endpoint").asText(), config.path("transport").asText(),
                auth.headers(), context.timeout(), 20 * 1024 * 1024, control))) {
            var actual = definitions.remote(session.listTools(), tool.timeoutSeconds());
            if (actual.stream()
                .noneMatch(value -> value.name().equals(tool.name()) && value.schemaHash().equals(tool.schemaHash()))) {
                throw new ApiException(HttpStatus.CONFLICT, "PLUGIN_TOOL_CHANGED",
                    "远程工具结构已经变化，未发送操作，请重新检查并发布。");
            }
            control.requireActive();
            var response = session.call(tool.name(), json.tree(arguments), context::beforeSend);
            if (!response.path("isError").asBoolean(false)) {
                schemas.result(tool.outputSchema() == null ? null : json.tree(tool.outputSchema()),
                    response.get("structuredContent"));
            }
            return json.object(response);
        }
    }

    private boolean containsSecret(JsonNode node, List<String> secrets) {
        if (node.isTextual() && secrets.stream().anyMatch(
            secret -> node.asText().equals(secret) || secret.length() >= 8 && node.asText().contains(secret))) {
            return true;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (secrets.stream().anyMatch(
                    secret -> field.getKey().equals(secret) || secret.length() >= 8 && field.getKey()
                        .contains(secret)) || containsSecret(field.getValue(), secrets)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (var child : node) {
                if (containsSecret(child, secrets)) {
                    return true;
                }
            }
        }
        return false;
    }
}
