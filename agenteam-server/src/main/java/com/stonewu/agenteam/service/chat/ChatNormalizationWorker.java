package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
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
    private final String worker = UUID.randomUUID().toString();

    public ChatNormalizationWorker(ChatNormalizationTransactions transactions, ChatConfiguration configuration) {
        this.transactions = transactions;
        this.configuration = configuration;
    }

    @Scheduled(fixedDelayString = "${chat.poll-interval-ms:5000}")
    public void poll() {
        if (!configuration.isEnabled() || !configuration.isWorkerEnabled()) {
            return;
        }
        for (int index = 0; index < Math.min(50, configuration.getBatchSize()); index++) {
            if (!runOnce()) {
                break;
            }
        }
    }

    public boolean runOnce() {
        var candidate = transactions.claim(worker);
        if (candidate.isEmpty()) {
            return false;
        }
        var lease = candidate.get();
        try {
            transactions.normalize(lease);
        } catch (RuntimeException failure) {
            var safe = new ChannelProviderException("CHAT_NORMALIZATION_FAILED", "聊天消息暂时无法处理。", failure);
            LOG.warn("聊天消息处理失败，企业={}，接收记录={}", lease.enterpriseId(), lease.receiptId(), safe);
            transactions.failed(lease, safe.code());
        }
        return true;
    }
}
