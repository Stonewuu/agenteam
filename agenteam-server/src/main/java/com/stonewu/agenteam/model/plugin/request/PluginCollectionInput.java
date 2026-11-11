package com.stonewu.agenteam.model.plugin.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 合集只接受工具引用，不允许客户端覆盖工具参数结构或降低确认要求。
 */
public record PluginCollectionInput(
    @NotNull(message = "{validation.required}") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "{error.plugin.pluginCollectionInput.icon.1}") String icon,
    @NotNull(message = "{validation.required}") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "{error.plugin.pluginCollectionInput.color.1}") String color,
    @NotNull(message = "{validation.required}") @Min(1) @Max(120) Integer timeoutSeconds,
    @NotNull(message = "{validation.required}") @Size(max = 100) List<@NotNull(message = "{validation.required}") @Valid Source> sources,
    @NotNull(message = "{validation.required}") @Size(max = 100) List<@NotNull(message = "{validation.required}") @Valid Selection> tools) {
    public record Source(@NotBlank(message = "{error.plugin.pluginCollectionInput.id.1}") @Size(max = 100) String id,
                         @NotNull(message = "{validation.required}") @Pattern(regexp = "builtin|mcp|java", message = "{error.plugin.pluginCollectionInput.type.1}") String type,
                         @Size(max = 100) String versionId,
                         @Pattern(regexp = "streamable_http|legacy_sse", message = "{error.plugin.pluginCollectionInput.transport.1}") String transport,
                         @Size(max = 2048) String endpoint, @Size(max = 100) String credentialId, JsonNode configuration) {
    }

    public record Selection(@NotBlank(message = "{error.plugin.pluginCollectionInput.id.1}") @Size(max = 100) String id,
                            @NotBlank(message = "{error.plugin.pluginCollectionInput.id.1}") @Size(max = 100) String sourceId,
                            @NotBlank(message = "{error.plugin.pluginCollectionInput.id.1}") @Pattern(regexp = "[A-Za-z0-9_.-]{1,128}", message = "{error.plugin.pluginCollectionInput.name.1}") String name,
                            @Min(1) @Max(120) Integer timeoutSeconds) {
    }
}
