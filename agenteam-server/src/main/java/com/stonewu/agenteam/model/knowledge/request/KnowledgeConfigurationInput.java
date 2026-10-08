package com.stonewu.agenteam.model.knowledge.request;

import jakarta.validation.constraints.*;


/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record KnowledgeConfigurationInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 40, message = "文字长度不符合要求。") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "请选择支持的选项或格式。") String icon,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "请选择支持的选项或格式。") String color,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 2000, message = "文字长度不符合要求。") String description,
    @NotNull(message = "请填写必填项。") @Pattern(regexp = "keyword", message = "请选择支持的选项或格式。") String retrievalMode,
    @NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 8L, message = "数值超出允许范围。") Integer maxResults,
    @NotNull(message = "请填写必填项。") @Min(value = 1000L, message = "数值超出允许范围。") @Max(value = 8000L, message = "数值超出允许范围。") Integer maxContextCharacters) {
}
