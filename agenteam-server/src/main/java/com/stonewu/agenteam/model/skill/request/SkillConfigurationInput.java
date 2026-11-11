package com.stonewu.agenteam.model.skill.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record SkillConfigurationInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 40, message = "{validation.textLength}") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "{validation.option}") String icon,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "{validation.option}") String color,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 1000, message = "{validation.textLength}") String scenario,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 2000, message = "{validation.textLength}") String inputDescription,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20000, message = "{validation.textLength}") String instructions,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 2000, message = "{validation.textLength}") String outputDescription,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 5000, message = "{validation.textLength}") String example,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> pluginVersionIds,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> knowledgeVersionIds,
    @NotNull(message = "{validation.required}") Boolean showInWorkspace) {
}
