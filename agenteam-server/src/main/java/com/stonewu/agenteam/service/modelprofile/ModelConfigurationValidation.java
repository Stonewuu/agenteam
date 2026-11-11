package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ReasoningEffort;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.model.modelprofile.request.ModelProviderWriteRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

/**
 * 只接受已接通的协议和明确填写的能力，不按模型名称猜测能力。
 */
@Component
public class ModelConfigurationValidation {

    public ModelProviderWriteRequest provider(ModelProviderWriteRequest value) {
        InputValidation.validate(value);
        String baseUrl = value.baseUrl().trim();
        try {
            URI uri = URI.create(baseUrl);
            boolean loopback = Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
            if (!("https".equals(uri.getScheme()) || ("http".equals(
                uri.getScheme()) && loopback)) || uri.getHost() == null
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw ApiException.invalidField("baseUrl",
                Messages.text("error.modelprofile.modelConfigurationValidation.provider.1"));
        }
        if (value.apiKey() != null && value.apiKey().codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("apiKey", Messages.text("error.modelprofile.modelConfigurationValidation.provider.2"));
        }
        return new ModelProviderWriteRequest(ResourceInput.text(value.name(), "name", 80, true), value.protocol(),
            baseUrl, value.apiKey(), value.enabled());
    }

    public ModelProfileWriteRequest model(ModelProfileWriteRequest value) {
        InputValidation.validate(value);
        ModelCapabilities capability = value.capabilities();
        if (capability.maxOutputTokens() > capability.maxContextTokens()) {
            throw ApiException.invalidField("capabilities.maxOutputTokens", Messages.text("error.modelprofile.modelConfigurationValidation.model.1"));
        }
        var normalized = new ModelCapabilities(capability.supportsTools(), capability.supportsTemperature(),
            capability.maxOutputTokens(),
            capability.maxContextTokens(), capability.inputTypes().stream().sorted().toList(),
            ReasoningEffort.VALUES.stream().filter(capability.reasoningEfforts()::contains).toList(), capability.protocolOptions());
        return new ModelProfileWriteRequest(value.providerId(), ResourceInput.text(value.name(), "name", 80, true),
            ResourceInput.text(value.modelName(), "modelName", 128, true), normalized, value.enabled());
    }
}
