package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 仅当前持有长连接的进程可以发送企微消息；不经过进程间转发重复发送。 */
@Component
public class ChatConnectionRegistry {
    public record Owned(IntegrationApplication application, String botId, long leaseVersion,
            ChatConnectionProvider.Connection connection, Runnable guard) {
    }
    private final Map<String, Owned> live = new ConcurrentHashMap<>();

    public void put(Owned owned) {
        live.put(owned.application().id(), owned);
    }

    public void remove(Owned owned) {
        live.remove(owned.application().id(), owned);
    }

    public boolean availableBot(String enterprise, String botId) {
        return live.values().stream().anyMatch(value -> value.application().enterpriseId().equals(enterprise)
            && value.botId().equals(botId) && value.connection().ready());
    }

    public List<String> localBotIds() {
        return live.values().stream().filter(value -> value.connection().ready()).map(Owned::botId).toList();
    }

    public Owned require(IntegrationApplication application) {
        var owned = live.get(application.id());
        if (owned == null || !owned.application().equals(application) || !owned.connection().ready()) {
            throw new ChannelProviderException("CHAT_CONNECTION_UNAVAILABLE", "当前进程没有此机器人的有效长连接。");
        }
        return owned;
    }
}
