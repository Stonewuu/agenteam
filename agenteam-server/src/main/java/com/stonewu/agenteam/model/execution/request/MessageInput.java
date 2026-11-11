package com.stonewu.agenteam.model.execution.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 用户提交的正文和明确选中的资料，不接受执行结果或身份字段。
 */
public record MessageInput(
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 20000, message = "{validation.textLength}") String text,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> attachmentIds,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 3, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String> skillVersionIds,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid KnowledgeReference> knowledgeReferences,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 10, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 2048, message = "{validation.textLength}") String> links,
    @Valid ModelSelection modelSelection) {
    public MessageInput(String text, List<String> attachmentIds, List<String> skillVersionIds,
                        List<KnowledgeReference> knowledgeReferences, List<String> links) {
        this(text, attachmentIds, skillVersionIds, knowledgeReferences, links, null);
    }

    public record KnowledgeReference(
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String documentId,
        @JsonProperty(value = "generation", required = true) @Min(value = 1L, message = "{validation.range}") @Max(value = 1000000000L, message = "{validation.range}") int generation) {
    }
}
