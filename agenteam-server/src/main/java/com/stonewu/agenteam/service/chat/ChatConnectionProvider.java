package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;

/** 平台协议只负责认证、心跳与传输，不参与协作调度。 */
public interface ChatConnectionProvider {
    String providerCode();

    Connection open(IntegrationApplication application, String secret, Consumer<JsonNode> events);

    default Connection openInteractive(IntegrationApplication application, String secret, Function<JsonNode, JsonNode> events) {
        return open(application, secret, event -> events.apply(event));
    }

    interface Connection extends AutoCloseable {
        boolean ready();
        void tick(Instant now);
        default boolean replaced() {
            return false;
        }
        default JsonNode request(String requestId, String command, JsonNode body, Runnable beforeSend) {
            throw ChatPlatformRegistry.unavailable();
        }
        @Override
        void close();
    }
}
