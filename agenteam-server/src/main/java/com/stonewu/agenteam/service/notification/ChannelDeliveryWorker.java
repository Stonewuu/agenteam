package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.ChannelRateLimiter;
import com.stonewu.agenteam.service.integration.IntegrationProviderRegistry;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 凭证、限流和实际请求都在事务外执行；取得响应后只重试保存结果，不重发已确认的请求。 */
@Service
public class ChannelDeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelDeliveryWorker.class);
    private final ChannelDeliveryTransactions transactions;
    private final IntegrationTokenService tokens;
    private final ChannelRateLimiter limiter;
    private final IntegrationProviderRegistry providers;
    private final String workerId = "channel-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("channel-lease-", 0).factory());

    public ChannelDeliveryWorker(ChannelDeliveryTransactions transactions, IntegrationTokenService tokens,
                                  ChannelRateLimiter limiter, IntegrationProviderRegistry providers) {
        this.transactions = transactions;
        this.tokens = tokens;
        this.limiter = limiter;
        this.providers = providers;
    }

    public boolean runOnce() {
        JobLease lease;
        try {
            var claimed = transactions.claim(workerId);
            if (claimed.isEmpty()) {
                return false;
            }
            lease = claimed.get();
        } catch (RuntimeException failure) {
            LOG.warn("通知发送工作暂时无法领取", failure);
            return false;
        }
        var current = new AtomicBoolean(true);
        var renewal = heartbeat.scheduleAtFixedRate(() -> renew(lease, current), 15, 15, TimeUnit.SECONDS);
        ChannelDeliveryTransactions.Started started = null;
        try {
            var prepared = transactions.prepare(lease);
            if (prepared == null) {
                return true;
            }
            ChannelAccessToken token = tokens.get(prepared.application());
            var delay = limiter.acquire(prepared.application(), prepared.recipient(), prepared.rateLimits());
            if (!delay.isZero()) {
                transactions.defer(lease, delay.plusMillis(100), "CHANNEL_RATE_WAIT", "已达到此应用或接收人的发送频率，正在等待可发送时间。");
                return true;
            }
            if (!current.get() || !transactions.renew(lease)) {
                return true;
            }
            started = transactions.begin(lease);
            if (started == null) {
                return true;
            }
            var result = providers.sender(prepared.application().providerCode()).send(prepared.application(), token, started.message().payload());
            if (result.outcome() == ChannelSendResult.Outcome.TOKEN_EXPIRED) {
                tokens.invalidate(prepared.application(), token);
            }
            saveResult(lease, started.attemptId(), result);
        } catch (RuntimeException failure) {
            // 第三方异常先由适配器移除敏感文本，其他异常也不能直接带入公开结果。
            LOG.warn("通知发送暂未完成，工作编号 {}", lease.id(), failure);
            if (started != null) {
                saveResult(lease, started.attemptId(), new ChannelSendResult(ChannelSendResult.Outcome.UNKNOWN, null, null,
                    "CHANNEL_RESULT_UNKNOWN", "请求可能已发出，尚未取得可确认的结果。", null, null, null));
            } else {
                deferFailure(lease, failure instanceof ChannelProviderException channel ? channel.code() : "CHANNEL_PREPARATION_FAILED");
            }
        } finally {
            renewal.cancel(false);
        }
        return true;
    }

    private void renew(JobLease lease, AtomicBoolean current) {
        try {
            if (!transactions.renew(lease)) {
                current.set(false);
            }
        } catch (RuntimeException failure) {
            current.set(false);
            LOG.warn("通知发送工作无法续期，工作编号 {}", lease.id(), failure);
        }
    }

    private void deferFailure(JobLease lease, String code) {
        try {
            transactions.defer(lease, Duration.ofSeconds(30), code, "发送准备暂未完成，稍后会继续尝试。");
        } catch (RuntimeException failure) {
            LOG.warn("通知等待结果暂时无法保存，工作编号 {}", lease.id(), failure);
        }
    }

    private void saveResult(JobLease lease, String attempt, ChannelSendResult result) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                transactions.complete(lease, attempt, result);
                return;
            } catch (RuntimeException failure) {
                LOG.warn("通知平台结果第 {} 次保存失败，工作编号 {}", retry + 1, lease.id(), failure);
            }
        }
    }

    @PreDestroy
    public void close() {
        heartbeat.shutdownNow();
    }
}
