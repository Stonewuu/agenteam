package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.model.chat.entity.ChatWorkRequested.Stage;
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
    private final ChatWorkCoordinator work;

    public ChatRoutingWorker(ChatRoutingService routing, ChatConfiguration chat, ChatWorkCoordinator work) {
        this.routing = routing;
        this.chat = chat;
        this.work = work;
    }

    @Scheduled(fixedDelayString = "${chat.routing-poll-interval-ms:2000}")
    public void poll() {
        work.wake(Stage.ROUTE);
    }

    public void processPending() {
        if (!chat.isWorkerEnabled()) {
            return;
        }
        for (var message : routing.pending()) {
            try {
                routing.route(message.getEnterpriseId(), message.getId());
            } catch (RuntimeException failure) {
                LOG.warn(Messages.log("log.chat.chatRoutingWorker.processPending.1"), message.getEnterpriseId(), message.getId(),
                    new ChannelProviderException("CHAT_ROUTING_FAILED", Messages.text("error.chat.chatRoutingWorker.processPending.1"), failure));
                if (failure instanceof ApiException api) {
                    routing.rejected(message.getEnterpriseId(), message.getId(), api.code());
                }
            }
        }
    }
}
