package com.stonewu.agenteam.mapper.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 旧发布配置集中读取；草稿保存只保留来源和工具引用的新结构。
 */
@Component
public class PluginConfigurationMapper {
    private final ResourceJson json;
    private final ResourceMapper resources;

    public PluginConfigurationMapper(ResourceJson json, ResourceMapper resources) {
        this.json = json;
        this.resources = resources;
    }

    public JsonNode normalize(String enterprise, JsonNode config) {
        ObjectNode result = config.deepCopy();
        if (!config.has("sources")) {
            boolean builtin = config.path("pluginType").asText().equals("builtin");
            String sourceId = builtin ? builtinResourceId(enterprise, config.path("builtinCode").asText()) : "remote";
            ObjectNode source = json.tree(Map.of("id", sourceId, "type", builtin ? "builtin" : "mcp")).deepCopy();
            if (builtin) {
                var resource = resources.find(enterprise, sourceId, false, false).orElse(null);
                source.put("versionId", resource == null ? null : resource.publishedVersionId());
            } else {
                for (var key : List.of("transport", "endpoint", "credentialId")) {
                    source.set(key, config.path(key));
                }
            }
            result.putArray("sources").add(source);
            var selections = result.putArray("tools");
            for (var name : config.path("enabledToolNames")) {
                selections.addObject().put("id", entryId(sourceId, name.asText()))
                    .put("sourceId", sourceId).put("name", name.asText());
            }
        }
        result.remove(List.of("pluginType", "builtinCode", "transport", "endpoint", "credentialId", "enabledToolNames",
            "implementationVersion"));
        var used = new HashSet<String>();
        result.path("tools").forEach(tool -> used.add(tool.path("sourceId").asText()));
        var retained = new ArrayList<JsonNode>();
        result.path("sources").forEach(source -> {
            if (source.path("type").asText().equals("mcp") || used.contains(source.path("id").asText())) {
                retained.add(source);
            }
        });
        result.putArray("sources").addAll(retained);
        return result;
    }

    public static boolean modern(JsonNode config) {
        return config.has("sources");
    }

    public static String mode(JsonNode config) {
        if (!modern(config)) {
            return config.path("pluginType").asText();
        }
        for (var source : config.path("sources")) {
            if (source.path("type").asText().equals("mcp")) {
                return "mcp";
            }
        }
        return "collection";
    }

    /**
     * 远程插件目前只有一个来源，连接检查沿用同一份实际连接信息。
     */
    public static JsonNode connection(JsonNode config) {
        if (!modern(config)) {
            return config;
        }
        for (var source : config.path("sources")) {
            if (source.path("type").asText().equals("mcp")) {
                ObjectNode result = source.deepCopy();
                result.put("pluginType", "mcp").putNull("builtinCode");
                result.set("timeoutSeconds", config.path("timeoutSeconds"));
                if (!result.has("credentialId")) {
                    result.putNull("credentialId");
                }
                return result;
            }
        }
        return config;
    }

    public static List<String> selectedNames(JsonNode config) {
        var names = new ArrayList<String>();
        if (modern(config)) {
            config.path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        } else {
            config.path("enabledToolNames").forEach(name -> names.add(name.asText()));
        }
        return List.copyOf(names);
    }

    public static List<DependencyBinding> dependencies(JsonNode config) {
        var result = new ArrayList<DependencyBinding>();
        if (modern(config)) {
            for (var source : config.path("sources")) {
                if (source.path("type").asText().equals("builtin") && source.hasNonNull("versionId")) {
                    result.add(new DependencyBinding(source.path("versionId").asText(), "plugin",
                        "sources/" + source.path("id").asText(), result.size()));
                }
            }
        }
        return List.copyOf(result);
    }

    public static JsonNode source(JsonNode config, String id) {
        for (var source : config.path("sources")) {
            if (source.path("id").asText().equals(id)) {
                return source;
            }
        }
        throw ApiException.invalidField("config.tools", "所选工具的来源不存在，请重新选择。");
    }

    public static String builtinResourceId(String enterprise, String code) {
        return stableId("builtin-plugin:" + enterprise + ":" + code);
    }

    public static String entryId(String source, String name) {
        return stableId("tool-entry:" + source + ":" + name);
    }

    private static String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
