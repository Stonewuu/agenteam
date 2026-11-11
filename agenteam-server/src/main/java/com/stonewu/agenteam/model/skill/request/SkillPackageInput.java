package com.stonewu.agenteam.model.skill.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record SkillPackageInput(
    @NotNull(message = "{validation.required}") @Min(value = 1L, message = "{validation.range}") @Max(value = 1L, message = "{validation.range}") Integer formatVersion,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 500, message = "{validation.textLength}") String description,
    @NotNull(message = "{validation.required}") @Valid Config config,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 20, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid DependenciesItem> dependencies) {
    public record Config(
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 40, message = "{validation.textLength}") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "{validation.option}") String icon,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "{validation.option}") String color,
        @NotNull(message = "{validation.required}") @Size(min = 0, max = 1000, message = "{validation.textLength}") String scenario,
        @NotNull(message = "{validation.required}") @Size(min = 0, max = 2000, message = "{validation.textLength}") String inputDescription,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 20000, message = "{validation.textLength}") String instructions,
        @NotNull(message = "{validation.required}") @Size(min = 0, max = 2000, message = "{validation.textLength}") String outputDescription,
        @NotNull(message = "{validation.required}") @Size(min = 0, max = 5000, message = "{validation.textLength}") String example,
        @NotNull(message = "{validation.required}") Boolean showInWorkspace) {
    }

    public record DependenciesItem(
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:plugin|knowledge)", message = "{validation.option}") String kind,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name) {
    }
}
