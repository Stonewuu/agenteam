package com.stonewu.agenteam.model.modelprofile.entity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.Map;

/**
 * 管理员明确配置的原生参数与计量能力，不按型号名称猜测厂商能力。
 */
public record ModelProtocolOptions(
    @Pattern(regexp = "(?:effort|budget|adaptive|level)", message = "{error.modelprofile.modelProtocolOptions.reasoningMode.1}") String reasoningMode,
    @Size(max = 7, message = "{error.modelprofile.modelCapabilities.reasoningEfforts.1}")
    Map<@Pattern(regexp = ReasoningEffort.PATTERN, message = "{validation.modelprofile.modelCapabilities.reasoningEfforts.2}") String,
        @Min(value = 0, message = "{validation.modelprofile.modelProtocolOptions.thinkingBudgets.1}") @Max(value = 1000000, message = "{validation.modelprofile.modelProtocolOptions.thinkingBudgets.2}") Integer> thinkingBudgets,
    @Size(max = 7, message = "{error.modelprofile.modelCapabilities.reasoningEfforts.1}")
    Map<@Pattern(regexp = ReasoningEffort.PATTERN, message = "{validation.modelprofile.modelCapabilities.reasoningEfforts.2}") String,
        @Pattern(regexp = "(?:minimal|low|medium|high|max)", message = "{validation.modelprofile.modelProtocolOptions.thinkingLevels.1}") String> thinkingLevels,
    @Min(value = 0, message = "{error.modelprofile.modelProtocolOptions.cacheTtlSeconds.1}") @Max(value = 3600, message = "{error.modelprofile.modelProtocolOptions.cacheTtlSeconds.2}") Integer cacheTtlSeconds,
    @Pattern(regexp = "(?:standard|deepseek)", message = "{error.modelprofile.modelProtocolOptions.usageFormat.1}") String usageFormat,
    @Size(max = 4, message = "{error.modelprofile.modelProtocolOptions.notApplicableUsage.1}") @UniqueElements(message = "{error.modelprofile.modelProtocolOptions.notApplicableUsage.2}")
    List<@Pattern(regexp = "(?:cache_read|cache_write|reasoning|tool_use_prompt)", message = "{validation.modelprofile.modelProtocolOptions.notApplicableUsage.1}") String> notApplicableUsage,
    @Pattern(regexp = "(?:default|auto|flex|priority|standard_only)", message = "{error.modelprofile.modelProtocolOptions.serviceTier.1}") String serviceTier,
    @Min(value = 1, message = "{error.modelprofile.modelProtocolOptions.version.1}") @Max(value = 1, message = "{error.modelprofile.modelProtocolOptions.version.1}") Integer version) {

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

}
