package com.stonewu.agenteam.model.knowledge.request;

import jakarta.validation.constraints.*;


/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record KnowledgeConfigurationInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 40, message = "{validation.textLength}") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "{validation.option}") String icon,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "{validation.option}") String color,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 2000, message = "{validation.textLength}") String description,
    @NotNull(message = "{validation.required}") @Pattern(regexp = "keyword", message = "{validation.option}") String retrievalMode,
    @NotNull(message = "{validation.required}") @Min(value = 1L, message = "{validation.range}") @Max(value = 8L, message = "{validation.range}") Integer maxResults,
    @NotNull(message = "{validation.required}") @Min(value = 1000L, message = "{validation.range}") @Max(value = 8000L, message = "{validation.range}") Integer maxContextCharacters) {
}
