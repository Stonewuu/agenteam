package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.wecom.WecomMemberAliases;
import com.stonewu.agenteam.model.chat.entity.ChatWorkRequested.Stage;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 连接只负责持久接收，规范化失败可在重启后继续处理。 */
@Component
public class ChatNormalizationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ChatNormalizationWorker.class);
    private final ChatNormalizationTransactions transactions;
    private final ChatConfiguration configuration;
    private final WecomMemberAliases wecomMembers;
    private final ChatWorkCoordinator work;
    private final String worker = UUID.randomUUID().toString();

    public ChatNormalizationWorker(ChatNormalizationTransactions transactions, ChatConfiguration configuration, WecomMemberAliases wecomMembers,
            ChatWorkCoordinator work) {
        this.transactions = transactions;
        this.configuration = configuration;
        this.wecomMembers = wecomMembers;
        this.work = work;
    }

    @Scheduled(fixedDelayString = "${chat.poll-interval-ms:5000}")
    public void poll() {
        if (!configuration.isWorkerEnabled()) {
            return;
        }
        work.wake(Stage.RECEIVE);
    }

    public boolean runOnce() {
        var candidate = transactions.claim(worker);
        if (candidate.isEmpty()) {
            return false;
        }
        var lease = candidate.get();
        try {
            wecomMembers.prepare(lease);
            transactions.normalize(lease);
        } catch (RuntimeException failure) {
            if (failure instanceof ApiException api && api.getStatusCode().is4xxClientError() && api.getStatusCode().value() != 429) {
                transactions.rejected(lease, api.code());
                LOG.info(Messages.log("log.chat.chatNormalizationWorker.runOnce.1"), lease.enterpriseId(), lease.receiptId(), api.code());
                return true;
            }
            var safe = new ChannelProviderException("CHAT_NORMALIZATION_FAILED", Messages.text("error.chat.chatNormalizationWorker.runOnce.1"), failure);
            LOG.warn(Messages.log("log.chat.chatNormalizationWorker.runOnce.2"), lease.enterpriseId(), lease.receiptId(), safe);
            transactions.failed(lease, safe.code());
        }
        return true;
    }
}
