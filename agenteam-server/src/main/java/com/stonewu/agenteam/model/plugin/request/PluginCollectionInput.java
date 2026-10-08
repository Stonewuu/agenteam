package com.stonewu.agenteam.model.plugin.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 合集只接受工具引用，不允许客户端覆盖工具参数结构或降低确认要求。
 */
public record PluginCollectionInput(
    @NotNull(message = "请填写必填项。") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "请选择支持的图标。") String icon,
    @NotNull(message = "请填写必填项。") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "请选择支持的颜色。") String color,
    @NotNull(message = "请填写必填项。") @Min(1) @Max(120) Integer timeoutSeconds,
    @NotNull(message = "请填写必填项。") @Size(max = 100) List<@NotNull(message = "请填写必填项。") @Valid Source> sources,
    @NotNull(message = "请填写必填项。") @Size(max = 100) List<@NotNull(message = "请填写必填项。") @Valid Selection> tools) {
    public record Source(@NotBlank(message = "请填写此项内容。") @Size(max = 100) String id,
                         @NotNull(message = "请填写必填项。") @Pattern(regexp = "builtin|mcp", message = "请选择当前支持的工具来源。") String type,
                         @Size(max = 100) String versionId,
                         @Pattern(regexp = "streamable_http|legacy_sse", message = "请选择支持的连接方式。") String transport,
                         @Size(max = 2048) String endpoint, @Size(max = 100) String credentialId) {
    }

    public record Selection(@NotBlank(message = "请填写此项内容。") @Size(max = 100) String id,
                            @NotBlank(message = "请填写此项内容。") @Size(max = 100) String sourceId,
                            @NotBlank(message = "请填写此项内容。") @Pattern(regexp = "[A-Za-z0-9_.-]{1,128}", message = "所选工具编号无效。") String name,
                            @Min(1) @Max(120) Integer timeoutSeconds) {
    }
}
