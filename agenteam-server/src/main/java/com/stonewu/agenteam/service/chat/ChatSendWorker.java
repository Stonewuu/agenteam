package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.ChannelRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 仅发送已保存的消息，网络调用在数据库事务外进行。 */
@Component
public class ChatSendWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ChatSendWorker.class);
    private final ChatSendTransactions transactions;
    private final ChatPlatformRegistry platforms;
    private final ChatConfiguration configuration;
    private final ChannelRateLimiter rates;
    private final String owner = UUID.randomUUID().toString();

    public ChatSendWorker(ChatSendTransactions transactions, ChatPlatformRegistry platforms, ChatConfiguration configuration, ChannelRateLimiter rates) {
        this.transactions = transactions;
        this.platforms = platforms;
        this.configuration = configuration;
        this.rates = rates;
    }

    @Scheduled(scheduler = "chatBackgroundScheduler", fixedDelayString = "${chat.send-poll-interval-ms:1000}")
    public void poll() {
        if (!configuration.isWorkerEnabled()) {
            return;
        }
        try {
            for (int index = 0; index < Math.min(50, configuration.getBatchSize()); index++) {
                if (!runOnce()) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("聊天发送后台工作未能完成", new ChannelProviderException("CHAT_SEND_WORKER_FAILED", "消息发送稍后重试。", failure));
        }
    }

    public boolean runOnce() {
        var candidate = transactions.claim(owner);
        if (candidate.isEmpty()) {
            return false;
        }
        var lease = candidate.get();
        var submitted = new AtomicBoolean();
        try {
            var prepared = transactions.prepare(lease);
            if (prepared.payload().path("messageType").asText().equals("file") && !prepared.payload().path("content").has("file_key")
                && !prepared.payload().path("content").has("media_id")) {
                String key = platforms.adapter(prepared.application().providerCode()).upload(prepared.application(), transactions.uploadFile(lease), () -> transactions.touch(lease));
                transactions.uploaded(lease, key);
                prepared = transactions.prepare(lease);
            }
            var wait = rates.acquireConversation(prepared.application(), prepared.platformScope(), prepared.conversation(),
                List.of(new ChannelRateLimit("chat_second", false, 1, 4, false, 0)));
            if (wait.isZero()) {
                wait = rates.acquire(prepared.application(), "chat", List.of(new ChannelRateLimit("chat_application", false, 1, 20, false, 0)));
            }
            if (!wait.isZero()) {
                transactions.defer(lease, wait, "CHAT_RATE_LIMITED", false);
                return true;
            }
            var preparedPayload = prepared.payload();
            var receipt = platforms.adapter(prepared.application().providerCode()).send(prepared.application(), preparedPayload, () -> {
                transactions.begin(lease, preparedPayload);
                submitted.set(true);
            });
            transactions.complete(lease, receipt);
        } catch (RuntimeException failure) {
            if (!submitted.get() && failure instanceof ApiException api
                    && List.of("CHAT_REPLY_MODE_CHANGED", "CHAT_REPLY_MODE_SKIP", "CHAT_FILE_SEND_FORBIDDEN").contains(api.code())) {
                transactions.defer(lease, Duration.ZERO, api.code(), !api.code().equals("CHAT_REPLY_MODE_CHANGED"));
                return true;
            }
            var safe = new ChannelProviderException("CHAT_SEND_FAILED", "聊天消息暂时无法发送。", failure);
            LOG.warn("聊天发送失败，企业={}，发送记录={}", lease.enterpriseId(), lease.outboundId(), safe);
            if (submitted.get()) {
                transactions.complete(lease, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.UNKNOWN, null, null,
                    "CHAT_SEND_UNKNOWN", "请求可能已经发送，平台结果尚未确认。", null, null, null), null));
            } else {
                transactions.defer(lease, Duration.ofSeconds(30), failure instanceof ApiException api ? api.code() : safe.code(), failure instanceof ApiException);
            }
        }
        return true;
    }
}
