package com.stonewu.agenteam.service.resource.validation;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.request.DataConfigurationInput;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 文件数据源不含外部连接；远程数据源发布前必须完成受控连接检查。
 */
@Component
public class DataConfigurationValidator implements ResourceConfigValidator {
    private final ConnectionValidationEvidence evidence;

    public DataConfigurationValidator(ConnectionValidationEvidence evidence) {
        this.evidence = evidence;
    }

    @Override
    public ResourceKind kind() {
        return ResourceKind.DATA;
    }

    @Override
    public void draft(JsonNode config) {
        InputValidation.read(config, DataConfigurationInput.class, "config");
        JsonNode connection = config.path("connection");
        switch (config.path("sourceType").asText()) {
            case "file" -> {
                InputValidation.fields(connection, "config.connection");
                if (!config.path("credentialId").isNull()) {
                    throw ApiException.invalidField("config.credentialId", Messages.text("error.resource.dataConfigurationValidator.draft.1"));
                }
            }
            case "mysql" -> {
                InputValidation.fields(connection, "config.connection", "host", "port", "database");
                InputValidation.required(connection, "config.connection", "host", "port", "database");
            }
            case "http" -> {
                InputValidation.fields(connection, "config.connection", "endpoint", "queryParameters");
                InputValidation.required(connection, "config.connection", "endpoint", "queryParameters");
                if (!connection.path("endpoint").asText().startsWith("https://")) {
                    throw ApiException.invalidField("config.connection.endpoint", Messages.text("error.resource.dataConfigurationValidator.draft.2"));
                }
            }
            default -> throw ApiException.invalidField("config.sourceType", Messages.text("error.resource.dataConfigurationValidator.draft.3"));
        }
    }

    @Override
    public void validatePublished(JsonNode config) {
        draft(config);
        if (config.path("sourceType").asText().equals("mysql") && !config.path("credentialId").isTextual()) {
            throw ApiException.invalidField("config.credentialId", Messages.text("error.resource.dataConfigurationValidator.validatePublished.1"));
        }
    }

    @Override
    public void publish(AuthContext actor, ResourceRecord resource) {
        use(actor, resource.config());
        if (!resource.config().path("sourceType").asText().equals("file")) {
            evidence.checked(resource);
        }
    }

    @Override
    public void use(AuthContext actor, JsonNode config) {
        String type = config.path("sourceType").asText();
        if (!type.equals("file")) {
            evidence.credential(actor,
                config.path("credentialId").isNull() ? null : config.path("credentialId").asText(),
                type.equals("mysql") ? Set.of("database") : Set.of("bearer", "basic", "api_key"));
        }
    }
}
