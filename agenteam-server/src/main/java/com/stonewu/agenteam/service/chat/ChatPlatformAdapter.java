package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.service.file.FileContentStorage;

import java.time.Duration;
import java.util.List;
import java.io.InputStream;
import java.util.function.Supplier;

/** 平台网络调用与内部任务分离，适配器不得自动重试业务发送。 */
public interface ChatPlatformAdapter {
    String providerCode();

    BotIdentity identity(IntegrationApplication application);

    ConversationPage conversations(IntegrationApplication application, String cursor);

    SendReceipt send(IntegrationApplication application, JsonNode preparedPayload, Runnable beforeSend);

    default JsonNode present(JsonNode payload) {
        return payload;
    }

    default List<String> splitText(String text) {
        return ChatTextParts.split(text);
    }

    default FileContentStorage.Stored download(IntegrationApplication application, String messageId, JsonNode material) {
        throw ChatPlatformRegistry.unavailable();
    }

    default Downloaded fetch(IntegrationApplication application, String messageId, JsonNode material, String name) {
        return new Downloaded(download(application, messageId, material), name);
    }

    record Downloaded(FileContentStorage.Stored content, String name) {
    }

    default void checkHistory(IntegrationApplication application, String chatId) {
        throw ChatPlatformRegistry.unavailable();
    }

    default String upload(IntegrationApplication application, UploadFile file, Runnable beforeUpload) {
        throw ChatPlatformRegistry.unavailable();
    }

    record UploadFile(String name, String mediaType, long size, Supplier<InputStream> open) {
    }

    default Duration deduplicationWindow() {
        return Duration.ZERO;
    }

    record BotIdentity(String id, String name, JsonNode capabilities) {
    }

    record ConversationIdentity(String id, String name, String kind) {
    }

    record ConversationPage(List<ConversationIdentity> items, String nextCursor) {
    }

    record SendReceipt(ChannelSendResult result, String threadId) {
    }
}
