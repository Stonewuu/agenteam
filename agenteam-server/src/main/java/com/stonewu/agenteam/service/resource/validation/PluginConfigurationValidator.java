package com.stonewu.agenteam.service.resource.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.plugin.request.PluginCollectionInput;
import com.stonewu.agenteam.model.plugin.request.PluginConfigurationInput;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.plugin.BuiltinPluginRegistry;
import com.stonewu.agenteam.service.plugin.PluginCheckEvidence;
import com.stonewu.agenteam.service.plugin.PluginToolSources;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 插件工具必须来自服务端检查结果，发布事务不会临时连接外部服务。
 */
@Component
public class PluginConfigurationValidator implements ResourceConfigValidator {
    private final ConnectionValidationEvidence evidence;
    private final PluginCheckEvidence checks;
    private final BuiltinPluginRegistry builtins;
    private final OutboundAddressPolicy addresses;
    private final PluginToolSources sources;

    public PluginConfigurationValidator(ConnectionValidationEvidence evidence, PluginCheckEvidence checks,
                                        BuiltinPluginRegistry builtins, OutboundAddressPolicy addresses,
                                        PluginToolSources sources) {
        this.evidence = evidence;
        this.checks = checks;
        this.builtins = builtins;
        this.addresses = addresses;
        this.sources = sources;
    }

    @Override
    public ResourceKind kind() {
        return ResourceKind.PLUGIN;
    }

    @Override
    public void draft(JsonNode config) {
        if (PluginConfigurationMapper.modern(config)) {
            collection(config);
            return;
        }
        InputValidation.read(config, PluginConfigurationInput.class, "config");
        if (config.path("pluginType").asText().equals("builtin")) {
            if (!config.path("transport").isNull() || !config.path("endpoint").isNull() || !config.path("credentialId")
                .isNull()) {
                throw ApiException.invalidField("config", "内置插件不能设置远程地址、传输方式或连接凭据。");
            }
            builtins.require(config.path("builtinCode").asText());
        } else {
            if (!config.path("builtinCode").isNull() || !config.path("transport").isTextual() || !config.path(
                "endpoint").isTextual()) {
                throw ApiException.invalidField("config", "远程插件需要传输方式和服务地址，不能设置内置插件代码。");
            }
            addresses.validateUrl(config.path("endpoint").asText());
        }
    }

    @Override
    public void publish(AuthContext actor, ResourceRecord resource) {
        use(actor, resource.config());
        if (PluginConfigurationMapper.modern(resource.config())) {
            if (PluginConfigurationMapper.mode(resource.config()).equals("collection") && resource.config()
                .path("tools").isEmpty()) {
                throw ApiException.invalidField("config.tools", "请至少选择一个工具后发布。");
            }
            if (PluginConfigurationMapper.mode(resource.config()).equals("mcp")) {
                evidence.checked(resource);
            }
            sources.resolve(actor, resource);
            return;
        }
        boolean builtin = resource.config().path("pluginType").asText().equals("builtin");
        if (!builtin) {
            evidence.checked(resource);
        }
        var discovered = (builtin ? builtins.require(resource.config().path("builtinCode").asText())
            .tools() : checks.tools(resource))
            .stream().map(tool -> tool.name()).collect(Collectors.toSet());
        for (var name : resource.config().path("enabledToolNames")) {
            if (!discovered.contains(name.asText())) {
                throw ApiException.invalidField("config.enabledToolNames",
                    "所选工具不在当前检查结果中，请重新检查连接。");
            }
        }
    }

    @Override
    public void use(AuthContext actor, JsonNode config) {
        if (PluginConfigurationMapper.modern(config)) {
            for (var source : config.path("sources")) {
                if (source.path("type").asText().equals("builtin")) {
                    sources.builtin(actor, source);
                } else {
                    evidence.credential(actor, source.path("credentialId").asText(null),
                        Set.of("bearer", "basic", "api_key"));
                }
            }
            return;
        }
        evidence.credential(actor, config.path("credentialId").isNull() ? null : config.path("credentialId").asText(),
            Set.of("bearer", "basic", "api_key"));
    }

    @Override
    public List<DependencyBinding> dependencies(JsonNode config) {
        return PluginConfigurationMapper.dependencies(config);
    }

    private void collection(JsonNode config) {
        var input = InputValidation.read(config, PluginCollectionInput.class, "config");
        var ids = new HashSet<String>();
        for (var source : input.sources()) {
            if (!ids.add(source.id())) {
                throw ApiException.invalidField("config.sources", "同一工具来源只能选择一次。");
            }
            if (source.type().equals("builtin")) {
                if (source.versionId() == null || source.versionId().isBlank()) {
                    throw ApiException.invalidField("config.sources", "请选择工具来源的可用版本。");
                }
                if (source.transport() != null || source.endpoint() != null || source.credentialId() != null) {
                    throw ApiException.invalidField("config.sources", "内置工具来源不能设置远程连接。");
                }
            } else {
                if (input.sources().size() != 1 || source.versionId() != null) {
                    throw ApiException.invalidField("config.sources", "远程插件目前只支持一个独立连接。");
                }
                if (source.transport() == null || source.endpoint() == null || source.endpoint().isBlank()) {
                    throw ApiException.invalidField("config.sources", "请填写远程插件的连接信息。");
                }
                addresses.validateUrl(source.endpoint());
            }
        }
        var entries = new HashSet<String>();
        var selected = new HashSet<String>();
        for (var tool : input.tools()) {
            if (!ids.contains(tool.sourceId())) {
                throw ApiException.invalidField("config.tools", "所选工具的来源不存在。");
            }
            if (!entries.add(tool.id()) || !selected.add(tool.sourceId() + ":" + tool.name())) {
                throw ApiException.invalidField("config.tools", "请勿重复选择同一来源的工具。");
            }
        }
    }
}
