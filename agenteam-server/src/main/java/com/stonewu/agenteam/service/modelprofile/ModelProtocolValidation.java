package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 只接受原生接口实际支持的参数组合，思考预算由管理员明确填写。
 */
@Component
public class ModelProtocolValidation {
    public void validate(String protocol, ModelCapabilities capabilities) {
        var options = capabilities.protocolOptions();
        var efforts = capabilities.reasoningEfforts();
        String mode = options.reasoningMode();
        if (!efforts.isEmpty()) {
            boolean compatible = switch (protocol) {
                case "openai", "openai_responses" -> mode.equals("effort");
                case "anthropic" -> Set.of("budget", "adaptive").contains(mode);
                case "gemini" -> Set.of("budget", "level").contains(mode);
                default -> false;
            };
            if (!compatible) {
                throw invalid("reasoningMode", Messages.text("error.modelprofile.modelProtocolValidation.validate.1"));
            }
            var errors = new LinkedHashMap<String, List<String>>();
            for (String effort : efforts) {
                if (mode.equals("budget")) {
                    Integer budget = options.thinkingBudget(effort);
                    String field = "capabilities.protocolOptions.thinkingBudgets." + effort;
                    if ("none".equals(effort)) {
                        if (budget != 0) {
                            errors.put(field, List.of(Messages.text("error.modelprofile.modelProtocolValidation.validate.3")));
                        }
                    } else {
                        int minimum = protocol.equals("anthropic") ? 1024 : 1;
                        if (budget == null || budget < minimum || budget >= capabilities.maxOutputTokens()) {
                            errors.put(field, List.of(Messages.text("error.modelprofile.modelProtocolValidation.validate.2", minimum)));
                        }
                    }
                } else if (!mode.equals("effort") && !effort.equals("none") && options.thinkingLevel(effort) == null) {
                    errors.put("capabilities.protocolOptions.thinkingLevels." + effort,
                        List.of(Messages.text("error.modelprofile.modelProtocolValidation.validate.4")));
                }
            }
            if (!errors.isEmpty()) {
                throw ApiException.invalidFields(errors);
            }
        }
        if (!Set.of(0, 300, 3600).contains(options.cacheTtlSeconds())
            || (!protocol.equals("anthropic") && options.cacheTtlSeconds() != 0)) {
            throw invalid("cacheTtlSeconds", Messages.text("error.modelprofile.modelProtocolValidation.validate.5"));
        }
        if (options.usageFormat().equals("deepseek") && !protocol.equals("openai")) {
            throw invalid("usageFormat", Messages.text("error.modelprofile.modelProtocolValidation.validate.6"));
        }
        String tier = options.serviceTier();
        if (tier != null) {
            var allowed = switch (protocol) {
                case "openai", "openai_responses" -> Set.of("auto", "default", "flex", "priority");
                case "anthropic" -> Set.of("auto", "standard_only");
                default -> Set.<String>of();
            };
            if (!allowed.contains(tier)) {
                throw invalid("serviceTier", Messages.text("error.modelprofile.modelProtocolValidation.validate.7"));
            }
        }
    }

    private ApiException invalid(String field, String message) {
        return ApiException.invalidField("capabilities.protocolOptions." + field, message);
    }
}
