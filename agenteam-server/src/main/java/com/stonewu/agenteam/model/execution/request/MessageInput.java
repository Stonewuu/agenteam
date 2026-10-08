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
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 20000, message = "文字长度不符合要求。") String text,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> attachmentIds,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 3, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> skillVersionIds,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid KnowledgeReference> knowledgeReferences,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 2048, message = "文字长度不符合要求。") String> links,
    @Valid ModelSelection modelSelection) {
    public MessageInput(String text, List<String> attachmentIds, List<String> skillVersionIds,
                        List<KnowledgeReference> knowledgeReferences, List<String> links) {
        this(text, attachmentIds, skillVersionIds, knowledgeReferences, links, null);
    }

    public record KnowledgeReference(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String documentId,
        @JsonProperty(value = "generation", required = true) @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 1000000000L, message = "数值超出允许范围。") int generation) {
    }
}
