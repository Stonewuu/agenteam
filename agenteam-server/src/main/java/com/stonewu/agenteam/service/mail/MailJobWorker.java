package com.stonewu.agenteam.service.mail;

import com.stonewu.agenteam.mapper.mail.SmtpMailMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.service.background.BackgroundJobService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 邮件调用在数据库事务外执行，发送期间续期，结束后再按领取版本保存结果。
 */
@Service
public class MailJobWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailJobWorker.class);
    private final BackgroundJobService jobs;
    private final MailDeliveryService delivery;
    private final SmtpMailMapper smtp;
    private final String workerId = "mail-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("mail-lease-", 0).factory());

    public MailJobWorker(BackgroundJobService jobs, MailDeliveryService delivery, SmtpMailMapper smtp) {
        this.jobs = jobs;
        this.delivery = delivery;
        this.smtp = smtp;
    }

    public boolean runOnce() {
        JobLease lease;
        try {
            var claimed = jobs.claimMail(workerId);
            if (claimed.isEmpty()) {
                return false;
            }
            lease = claimed.get();
        } catch (RuntimeException exception) {
            LOGGER.warn("暂时无法领取邮件工作", exception);
            return false;
        }
        try {
            var prepared = delivery.prepare(lease);
            if (prepared.isEmpty() || !jobs.renew(lease)) {
                return true;
            }
            var renewal = heartbeat.scheduleAtFixedRate(() -> renew(lease), 10, 10, TimeUnit.SECONDS);
            try {
                var message = prepared.get();
                smtp.send(message.recipient(), message.subject(), message.text(), message.messageId());
                saveAcknowledgement(lease);
            } finally {
                renewal.cancel(false);
            }
        } catch (MailDeliveryException exception) {
            LOGGER.warn("邮件发送失败，工作编号 {}，错误代码 {}", lease.id(), exception.code(), exception);
            recordFailure(lease, exception.code(), exception.getMessage(), true);
        } catch (RuntimeException exception) {
            LOGGER.warn("邮件工作暂未完成，工作编号 {}", lease.id(), exception);
            recordFailure(lease, "MAIL_CONTENT_UNAVAILABLE", "邮件暂时无法处理，请重新发送。", false);
        }
        return true;
    }

    private void renew(JobLease lease) {
        try {
            jobs.renew(lease);
        } catch (RuntimeException exception) {
            LOGGER.warn("邮件工作暂时无法续期，工作编号 {}", lease.id(), exception);
        }
    }

    private void recordFailure(JobLease lease, String code, String message, boolean retryable) {
        try {
            delivery.failed(lease, code, message, retryable);
        } catch (RuntimeException exception) {
            LOGGER.warn("邮件结果暂时无法保存，工作编号 {}", lease.id(), exception);
        }
    }

    private void saveAcknowledgement(JobLease lease) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                delivery.succeeded(lease);
                return;
            } catch (RuntimeException exception) {
                // 重试保存同一次确认，不再次调用邮件服务。
                LOGGER.warn("邮件服务已经确认发送，但第 {} 次保存结果失败，工作编号 {}", attempt + 1, lease.id(),
                    exception);
            }
        }
    }

    @PreDestroy
    public void close() {
        heartbeat.shutdownNow();
    }
}
