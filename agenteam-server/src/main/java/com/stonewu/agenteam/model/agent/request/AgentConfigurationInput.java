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
@JsonIgnoreProperties({"researchSubagentEnabled", "subagents"})
public record AgentConfigurationInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 40, message = "文字长度不符合要求。") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "请选择支持的选项或格式。") String icon,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "请选择支持的选项或格式。") String color,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:chat|task|workflow)", message = "请选择支持的选项或格式。") String agentType,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 200, message = "文字长度不符合要求。") String businessRole,
    @JsonProperty(value = "modelProfileId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String modelProfileId,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 20000, message = "文字长度不符合要求。") String instructions,
    @DecimalMin(value = "0", message = "数值超出允许范围。") @DecimalMax(value = "2", message = "数值超出允许范围。") Double temperature,
    @Pattern(regexp = ReasoningEffort.PATTERN, message = "请选择有效的思考等级。") String reasoningEffort,
    @NotNull(message = "请填写必填项。") @Min(value = 0L, message = "最多执行步骤不能为负数，填写 0 表示无上限。") Integer maxSteps,
    @NotNull(message = "请填写必填项。") @Min(value = 0L, message = "执行时间上限不能为负数，填写 0 表示无上限。") Integer timeoutSeconds,
    @NotNull(message = "请填写必填项。") Boolean attachmentsEnabled,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 1000, message = "文字长度不符合要求。") String welcomeMessage,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 5, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 200, message = "文字长度不符合要求。") String> suggestedQuestions,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 20, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> skillVersionIds,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 20, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> pluginVersionIds,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> knowledgeVersionIds,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> dataVersionIds,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 5, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> workflowVersionIds,
    @JsonProperty(value = "entryWorkflowVersionId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String entryWorkflowVersionId,
    @NotNull(message = "请填写必填项。") @Min(value = 0L, message = "数值超出允许范围。") @Max(value = 100L, message = "数值超出允许范围。") Integer historyMessageLimit,
    @NotNull(message = "请填写必填项。") Boolean memoryEnabled,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 20, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 50, message = "文字长度不符合要求。") String> memoryFields,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 100, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid BusinessTermsItem> businessTerms,
    @Size(max = 8, message = "最多选择 8 个子智能体。") @UniqueElements(message = "不能重复选择相同版本。") List<@NotNull(message = "请选择智能体版本。") @Size(min = 1, max = 100, message = "版本编号长度不符合要求。") String> subagentVersionIds,
    Boolean dynamicSubagentEnabled,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 5, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 200, message = "文字长度不符合要求。") String> publicExamples) {
    public record BusinessTermsItem(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 50, message = "文字长度不符合要求。") String term,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 500, message = "文字长度不符合要求。") String meaning) {
    }
}
