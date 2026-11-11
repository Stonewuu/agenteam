package com.stonewu.agenteam.service.resource.validation;

import com.stonewu.agenteam.service.i18n.Messages;

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
                throw ApiException.invalidField("config", Messages.text("error.resource.pluginConfigurationValidator.draft.1"));
            }
            builtins.require(config.path("builtinCode").asText());
        } else {
            if (!config.path("builtinCode").isNull() || !config.path("transport").isTextual() || !config.path(
                "endpoint").isTextual()) {
                throw ApiException.invalidField("config", Messages.text("error.resource.pluginConfigurationValidator.draft.2"));
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
                throw ApiException.invalidField("config.tools", Messages.text("error.resource.pluginConfigurationValidator.publish.1"));
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
                    Messages.text("error.resource.pluginConfigurationValidator.publish.2"));
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
                throw ApiException.invalidField("config.sources", Messages.text("error.resource.pluginConfigurationValidator.collection.1"));
            }
            if (source.type().equals("builtin")) {
                if (source.versionId() == null || source.versionId().isBlank()) {
                    throw ApiException.invalidField("config.sources", Messages.text("error.resource.pluginConfigurationValidator.collection.2"));
                }
                if (source.transport() != null || source.endpoint() != null || source.credentialId() != null) {
                    throw ApiException.invalidField("config.sources", Messages.text("error.resource.pluginConfigurationValidator.collection.3"));
                }
            } else if (source.type().equals("java")) {
                if (source.versionId() == null || source.versionId().isBlank() || source.transport() != null
                    || source.endpoint() != null || source.configuration() == null || !source.configuration().isObject()
                    || input.sources().size() != 1) {
                    throw ApiException.invalidField("config.sources", Messages.text("error.resource.pluginConfigurationValidator.collection.4"));
                }
            } else {
                if (input.sources().size() != 1 || source.versionId() != null) {
                    throw ApiException.invalidField("config.sources", Messages.text("error.resource.pluginConfigurationValidator.collection.5"));
                }
                if (source.transport() == null || source.endpoint() == null || source.endpoint().isBlank()) {
                    throw ApiException.invalidField("config.sources", Messages.text("error.resource.pluginConfigurationValidator.collection.6"));
                }
                addresses.validateUrl(source.endpoint());
            }
        }
        var entries = new HashSet<String>();
        var selected = new HashSet<String>();
        for (var tool : input.tools()) {
            if (!ids.contains(tool.sourceId())) {
                throw ApiException.invalidField("config.tools", Messages.text("error.resource.pluginConfigurationValidator.collection.7"));
            }
            if (!entries.add(tool.id()) || !selected.add(tool.sourceId() + ":" + tool.name())) {
                throw ApiException.invalidField("config.tools", Messages.text("error.resource.pluginConfigurationValidator.collection.8"));
            }
        }
    }
}
