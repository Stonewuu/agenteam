package com.stonewu.agenteam.configuration.mail;

import com.stonewu.agenteam.service.mail.MailJobWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 可由独立工作进程启用邮件领取；接口进程可通过配置关闭自动领取。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agenteam.mail.worker-enabled", havingValue = "true", matchIfMissing = true)
public class MailSchedulingConfiguration {

    @Bean
    public MailPollingTask mailPollingTask(MailJobWorker worker) {
        return new MailPollingTask(worker);
    }

    public static final class MailPollingTask {

        private final MailJobWorker worker;

        public MailPollingTask(MailJobWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${agenteam.mail.poll-ms:1000}")
        public void poll() {
            worker.runOnce();
        }
    }
}
