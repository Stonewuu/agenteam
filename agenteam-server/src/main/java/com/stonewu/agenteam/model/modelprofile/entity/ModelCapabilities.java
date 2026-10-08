package com.stonewu.agenteam.model.modelprofile.entity;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理员明确填写的模型能力，不通过模型名称推断。
 */
public record ModelCapabilities(
    @NotNull(message = "请填写必填项。") Boolean supportsTools,
    @NotNull(message = "请填写必填项。") Boolean supportsTemperature,
    @NotNull(message = "请填写必填项。") @Min(value = 128L, message = "数值超出允许范围。") @Max(value = 1000000L, message = "数值超出允许范围。") Integer maxOutputTokens,
    @NotNull(message = "请填写必填项。") @Min(value = 128L, message = "数值超出允许范围。") @Max(value = 10000000L, message = "数值超出允许范围。") Integer maxContextTokens,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 2, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:text|image)", message = "请选择支持的选项或格式。") String> inputTypes,
    @Size(max = 7, message = "思考等级数量超出允许范围。") @UniqueElements(message = "不能包含重复的思考等级。")
    List<@NotNull(message = "请选择思考等级。") @Pattern(regexp = ReasoningEffort.PATTERN, message = "请选择支持的思考等级。") String> reasoningEfforts) {
    public ModelCapabilities {
        reasoningEfforts = reasoningEfforts == null ? List.of() : Collections.unmodifiableList(
            new ArrayList<>(reasoningEfforts));
    }

    public ModelCapabilities(Boolean supportsTools, Boolean supportsTemperature, Integer maxOutputTokens,
                             Integer maxContextTokens, List<String> inputTypes) {
        this(supportsTools, supportsTemperature, maxOutputTokens, maxContextTokens, inputTypes, List.of());
    }

    /**
     * 已使用的模型只允许补充思考等级，避免改变历史任务和已发布配置的含义。
     */
    public boolean canExtendTo(ModelCapabilities next) {
        return supportsTools.equals(next.supportsTools) && supportsTemperature.equals(next.supportsTemperature)
            && maxOutputTokens.equals(next.maxOutputTokens) && maxContextTokens.equals(next.maxContextTokens)
            && inputTypes.equals(next.inputTypes) && next.reasoningEfforts.containsAll(reasoningEfforts);
    }
}
