package com.stonewu.agenteam.model.agent.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stonewu.agenteam.model.modelprofile.entity.ReasoningEffort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
@JsonIgnoreProperties({"researchSubagentEnabled", "subagents", "historyMessageLimit"})
public record AgentConfigurationInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 40, message = "{validation.textLength}") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "{validation.option}") String icon,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "{validation.option}") String color,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:chat|task|workflow)", message = "{validation.option}") String agentType,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 200, message = "{validation.textLength}") String businessRole,
    @JsonProperty(value = "modelProfileId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String modelProfileId,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 20000, message = "{validation.textLength}") String instructions,
    @DecimalMin(value = "0", message = "{validation.range}") @DecimalMax(value = "2", message = "{validation.range}") Double temperature,
    @Pattern(regexp = ReasoningEffort.PATTERN, message = "{error.agent.agentConfigurationInput.reasoningEffort.1}") String reasoningEffort,
    @NotNull(message = "{validation.required}") @Min(value = 0L, message = "{error.agent.agentConfigurationInput.maxSteps.1}") Integer maxSteps,
    @NotNull(message = "{validation.required}") @Min(value = 0L, message = "{error.agent.agentConfigurationInput.timeoutSeconds.1}") Integer timeoutSeconds,
    @NotNull(message = "{validation.required}") Boolean attachmentsEnabled,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 1000, message = "{validation.textLength}") String welcomeMessage,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 5, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 200, message = "{validation.textLength}") String> suggestedQuestions,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 20, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> skillVersionIds,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 20, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> pluginVersionIds,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> knowledgeVersionIds,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> dataVersionIds,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 5, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> workflowVersionIds,
    @JsonProperty(value = "entryWorkflowVersionId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String entryWorkflowVersionId,
    @NotNull(message = "{validation.required}") Boolean memoryEnabled,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 20, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 50, message = "{validation.textLength}") String> memoryFields,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 100, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid BusinessTermsItem> businessTerms,
    @Size(max = 8, message = "{error.agent.agentConfigurationInput.subagentVersionIds.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.subagentVersionIds.2}") List<@NotNull(message = "{error.resource.agentConfigurationValidator.validateSubagents.1}") @Size(min = 1, max = 100, message = "{validation.agent.agentConfigurationInput.subagentVersionIds.1}") String> subagentVersionIds,
    Boolean dynamicSubagentEnabled,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 5, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 200, message = "{validation.textLength}") String> publicExamples) {
    public record BusinessTermsItem(
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 50, message = "{validation.textLength}") String term,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 500, message = "{validation.textLength}") String meaning) {
    }
}
