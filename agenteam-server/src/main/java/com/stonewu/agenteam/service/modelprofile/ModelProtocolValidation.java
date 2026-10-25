package com.stonewu.agenteam.service.modelprofile;

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
                throw invalid("reasoningMode", "当前接口不支持所选思考方式。");
            }
            for (String effort : efforts) {
                if (mode.equals("budget")) {
                    var budget = options.thinkingBudgets().get(effort);
                    if (budget == null || budget >= capabilities.maxOutputTokens()
                        || (protocol.equals("anthropic") && budget != 0 && budget < 1024)) {
                        throw invalid("thinkingBudgets", "请为每个思考等级填写小于最大输出长度的预算；Anthropic 启用思考时至少为 1024。");
                    }
                    if (effort.equals("none") != (budget == 0)) {
                        throw invalid("thinkingBudgets", "关闭思考的预算必须为 0，其他等级必须大于 0。");
                    }
                } else if (mode.equals("adaptive") || mode.equals("level")) {
                    String level = options.thinkingLevels().get(effort);
                    if ((!effort.equals("none") && level == null) || (protocol.equals("gemini") && effort.equals("none"))
                        || (protocol.equals("anthropic") && "minimal".equals(level))
                        || (protocol.equals("gemini") && "max".equals(level))) {
                        throw invalid("thinkingLevels", "请为每个等级填写当前接口支持的按思考强度设置。");
                    }
                }
            }
        }
        if (!Set.of(0, 300, 3600).contains(options.cacheTtlSeconds())
            || (!protocol.equals("anthropic") && options.cacheTtlSeconds() != 0)) {
            throw invalid("cacheTtlSeconds", "只有 Anthropic 可配置 5 分钟或 1 小时的缓存创建有效期。");
        }
        if (options.usageFormat().equals("deepseek") && !protocol.equals("openai")) {
            throw invalid("usageFormat", "DeepSeek 用量格式只适用于 OpenAI 兼容接口。");
        }
        String tier = options.serviceTier();
        if (tier != null) {
            var allowed = switch (protocol) {
                case "openai", "openai_responses" -> Set.of("auto", "default", "flex", "priority");
                case "anthropic" -> Set.of("auto", "standard_only");
                default -> Set.<String>of();
            };
            if (!allowed.contains(tier)) {
                throw invalid("serviceTier", "当前接口不支持所选服务档位。");
            }
        }
    }

    private ApiException invalid(String field, String message) {
        return ApiException.invalidField("capabilities.protocolOptions." + field, message);
    }
}
