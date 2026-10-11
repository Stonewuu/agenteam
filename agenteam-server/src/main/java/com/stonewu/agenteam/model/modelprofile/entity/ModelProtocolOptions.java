package com.stonewu.agenteam.model.modelprofile.entity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理员明确配置的原生参数与计量能力，不按型号名称猜测厂商能力。
 */
public record ModelProtocolOptions(
    @Pattern(regexp = "(?:effort|budget|adaptive|level)", message = "请选择支持的思考方式。") String reasoningMode,
    @Size(max = 7, message = "思考等级数量超出允许范围。")
    Map<@Pattern(regexp = ReasoningEffort.PATTERN, message = "请选择支持的思考等级。") String,
        @Min(value = 0, message = "思考预算不能为负数。") @Max(value = 1000000, message = "思考预算超出允许范围。") Integer> thinkingBudgets,
    @Size(max = 7, message = "思考等级数量超出允许范围。")
    Map<@Pattern(regexp = ReasoningEffort.PATTERN, message = "请选择支持的思考等级。") String,
        @Pattern(regexp = "(?:minimal|low|medium|high|max)", message = "请选择支持的原生思考等级。") String> thinkingLevels,
    @Min(value = 0, message = "缓存有效期不能为负数。") @Max(value = 3600, message = "缓存有效期超出允许范围。") Integer cacheTtlSeconds,
    @Pattern(regexp = "(?:standard|deepseek)", message = "请选择支持的用量格式。") String usageFormat,
    @Size(max = 4, message = "用量字段数量超出允许范围。") @UniqueElements(message = "用量字段不能重复。")
    List<@Pattern(regexp = "(?:cache_read|cache_write|reasoning|tool_use_prompt)", message = "请选择支持的用量字段。") String> notApplicableUsage,
    @Pattern(regexp = "(?:default|auto|flex|priority|standard_only)", message = "请选择支持的服务档位。") String serviceTier,
    @Min(value = 1, message = "协议配置版本不受支持。") @Max(value = 1, message = "协议配置版本不受支持。") Integer version) {

    public ModelProtocolOptions {
        version = version == null ? 1 : version;
        reasoningMode = reasoningMode == null ? "effort" : reasoningMode;
        thinkingBudgets = thinkingBudgets == null ? Map.of() : Map.copyOf(thinkingBudgets);
        thinkingLevels = thinkingLevels == null ? Map.of() : Map.copyOf(thinkingLevels);
        cacheTtlSeconds = cacheTtlSeconds == null ? 0 : cacheTtlSeconds;
        usageFormat = usageFormat == null ? "standard" : usageFormat;
        notApplicableUsage = notApplicableUsage == null ? List.of() : List.copyOf(notApplicableUsage);
    }

    public ModelProtocolOptions(String reasoningMode, Map<String, Integer> thinkingBudgets, Map<String, String> thinkingLevels,
                                 Integer cacheTtlSeconds, String usageFormat, List<String> notApplicableUsage, String serviceTier) {
        this(reasoningMode, thinkingBudgets, thinkingLevels, cacheTtlSeconds, usageFormat, notApplicableUsage, serviceTier, 1);
    }

    public static ModelProtocolOptions defaults() {
        return new ModelProtocolOptions(null, null, null, null, null, null, null);
    }

    public boolean canExtendTo(ModelProtocolOptions next) {
        return version.equals(next.version) && reasoningMode.equals(next.reasoningMode) && cacheTtlSeconds.equals(next.cacheTtlSeconds)
            && Objects.equals(serviceTier, next.serviceTier)
            && next.thinkingBudgets.entrySet().containsAll(thinkingBudgets.entrySet())
            && next.thinkingLevels.entrySet().containsAll(thinkingLevels.entrySet());
    }
}
