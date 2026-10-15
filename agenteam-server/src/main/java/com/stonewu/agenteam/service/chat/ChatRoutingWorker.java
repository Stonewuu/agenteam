package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.configuration.collaboration.CollaborationConfiguration;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 路由异常单独记录，不回滚已向平台确认接收的原始消息。 */
@Component
public class ChatRoutingWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ChatRoutingWorker.class);
    private final ChatRoutingService routing;
    private final ChatConfiguration chat;
    private final CollaborationConfiguration collaboration;

    public ChatRoutingWorker(ChatRoutingService routing, ChatConfiguration chat, CollaborationConfiguration collaboration) {
        this.routing = routing;
        this.chat = chat;
        this.collaboration = collaboration;
    }

    @Scheduled(fixedDelayString = "${chat.routing-poll-interval-ms:2000}")
    public void poll() {
        if (!chat.isEnabled() || !chat.isWorkerEnabled() || !collaboration.isEnabled()) {
            return;
        }
        for (var message : routing.pending()) {
            try {
                routing.route(message.getEnterpriseId(), message.getId());
            } catch (RuntimeException failure) {
                LOG.warn("聊天消息未能建立任务，企业={}，消息={}", message.getEnterpriseId(), message.getId(),
                    new ChannelProviderException("CHAT_ROUTING_FAILED", "消息暂时无法处理。", failure));
                if (failure instanceof ApiException api) {
                    routing.rejected(message.getEnterpriseId(), message.getId(), api.code());
                }
            }
        }
    }
}
