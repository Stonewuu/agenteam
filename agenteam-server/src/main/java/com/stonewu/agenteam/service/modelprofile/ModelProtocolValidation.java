package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

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
            for (String effort : efforts) {
                if (mode.equals("budget")) {
                    var budget = options.thinkingBudgets().get(effort);
                    if (budget == null || budget >= capabilities.maxOutputTokens()
                        || (protocol.equals("anthropic") && budget != 0 && budget < 1024)) {
                        throw invalid("thinkingBudgets", Messages.text("error.modelprofile.modelProtocolValidation.validate.2"));
                    }
                    if (effort.equals("none") != (budget == 0)) {
                        throw invalid("thinkingBudgets", Messages.text("error.modelprofile.modelProtocolValidation.validate.3"));
                    }
                } else if (mode.equals("adaptive") || mode.equals("level")) {
                    String level = options.thinkingLevels().get(effort);
                    if ((!effort.equals("none") && level == null) || (protocol.equals("gemini") && effort.equals("none"))
                        || (protocol.equals("anthropic") && "minimal".equals(level))
                        || (protocol.equals("gemini") && "max".equals(level))) {
                        throw invalid("thinkingLevels", Messages.text("error.modelprofile.modelProtocolValidation.validate.4"));
                    }
                }
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
