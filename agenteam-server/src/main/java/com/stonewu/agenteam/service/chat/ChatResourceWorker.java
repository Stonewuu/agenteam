package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 附件下载在事务外，扫描与解析复用既有文件后台工作。 */
@Component
public class ChatResourceWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ChatResourceWorker.class);
    private final ChatResourceTransactions transactions;
    private final ChatPlatformRegistry platforms;
    private final ChatConfiguration configuration;
    private final String owner = UUID.randomUUID().toString();

    public ChatResourceWorker(ChatResourceTransactions transactions, ChatPlatformRegistry platforms, ChatConfiguration configuration) {
        this.transactions = transactions;
        this.platforms = platforms;
        this.configuration = configuration;
    }

    @Scheduled(scheduler = "chatBackgroundScheduler", fixedDelayString = "${chat.resource-poll-interval-ms:2000}")
    public void poll() {
        if (!configuration.isWorkerEnabled()) {
            return;
        }
        try {
            for (var resource : transactions.awaitingInspection()) {
                transactions.inspected(resource.getEnterpriseId(), resource.getId());
            }
            runOnce();
        } catch (RuntimeException failure) {
            LOG.warn("聊天附件后台工作本轮未完成", new ChannelProviderException("CHAT_RESOURCE_WORKER_FAILED", "附件处理稍后继续。", failure));
        }
    }

    public boolean runOnce() {
        var candidate = transactions.claim(owner);
        if (candidate.isEmpty()) {
            return false;
        }
        var lease = candidate.get();
        try {
            var prepared = transactions.prepare(lease);
            var content = platforms.adapter(prepared.application().providerCode()).fetch(prepared.application(), prepared.platformMessageId(), prepared.material(), prepared.name());
            transactions.fetched(lease, prepared, content);
        } catch (RuntimeException failure) {
            String code = failure instanceof ApiException api ? api.code() : failure instanceof ChannelProviderException platform ? platform.code() : "CHAT_RESOURCE_DOWNLOAD_FAILED";
            LOG.warn("聊天附件处理失败，企业={}，资源={}", lease.enterpriseId(), lease.resourceId(),
                new ChannelProviderException(code, "平台附件暂时无法读取。", failure));
            transactions.failed(lease, code, !(failure instanceof ApiException));
        }
        return true;
    }
}
