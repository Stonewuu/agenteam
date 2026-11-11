package com.stonewu.agenteam.model.integration.request;

import com.stonewu.agenteam.service.i18n.Messages;

public record IntegrationSecretRequest(String secret) {
    @Override
    public String toString() {
        return Messages.log("log.integration.integrationSecretRequest.toString.1");
    }
}
