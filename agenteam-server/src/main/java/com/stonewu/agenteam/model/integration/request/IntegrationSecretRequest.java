package com.stonewu.agenteam.model.integration.request;

public record IntegrationSecretRequest(String secret) {
    @Override
    public String toString() {
        return "IntegrationSecretRequest[密钥已隐藏]";
    }
}
