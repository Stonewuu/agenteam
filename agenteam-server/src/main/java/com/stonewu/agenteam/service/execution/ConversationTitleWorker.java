package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ModelFailureMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.ConversationTitleRequested;
import com.stonewu.agenteam.service.background.BackgroundJobHeartbeat;
import com.stonewu.agenteam.service.background.BackgroundJobService;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 唤醒只安排有限后台工作；满载时保留数据库任务，绝不回到请求线程调用模型。 */
@Component
@ConditionalOnProperty(name = "execution.titles.enabled", havingValue = "true", matchIfMissing = true)
public class ConversationTitleWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationTitleWorker.class);
    private final BackgroundJobService jobs;
    private final BackgroundJobHeartbeat heartbeats;
    private final ConversationTitleService titles;
    private final ConversationTitleGenerator generator;
    private final String owner = "title-" + UUID.randomUUID();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger active = new AtomicInteger();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), Thread.ofVirtual().name("对话标题-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());

    public ConversationTitleWorker(BackgroundJobService jobs, BackgroundJobHeartbeat heartbeats,
                                   ConversationTitleService titles, ConversationTitleGenerator generator) {
        this.jobs = jobs;
        this.heartbeats = heartbeats;
        this.titles = titles;
        this.generator = generator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(ConversationTitleRequested requested) {
        wake();
    }

    @Scheduled(fixedDelay = 1000)
    public void wake() {
        for (int slot = active.get(); slot < 2 && !closed.get(); slot++) {
            if (!active.compareAndSet(slot, slot + 1)) {
                return;
            }
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException rejected) {
                active.decrementAndGet();
                LOG.debug("标题工作暂未唤醒，数据库任务将在下一轮继续处理，工作进程 {}", owner, rejected);
                return;
            }
        }
    }

    private void drain() {
        try {
            while (!closed.get()) {
                var lease = jobs.claimConversationTitle(owner);
                if (lease.isEmpty()) {
                    return;
                }
                process(lease.get());
            }
        } catch (RuntimeException failure) {
            LOG.warn("标题后台检查暂未完成，工作进程 {}", owner, failure);
        } finally {
            active.decrementAndGet();
        }
    }

    private void process(JobLease lease) {
        try (var guard = heartbeats.start(lease)) {
            var input = titles.prepare(lease);
            if (input.isPresent()) {
                String title = generator.generate(input.get(), () -> !closed.get() && guard.getAsBoolean());
                if (guard.getAsBoolean() && !closed.get()) {
                    titles.complete(lease, title);
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("自动标题未完成，工作编号 {}", lease.id(), failure);
            if (!closed.get()) {
                titles.failed(lease, retryable(Exceptions.unwrap(failure)));
            }
        }
    }

    static boolean retryable(Throwable failure) {
        if (failure instanceof CancellationException || failure instanceof IllegalArgumentException) {
            return false;
        }
        if (failure instanceof TimeoutException || failure instanceof TransientDataAccessException) {
            return true;
        }
        if (failure instanceof ApiException api) {
            return "MODEL_TEMPORARY_FAILURE".equals(api.code());
        }
        if (failure instanceof ResponseStatusException response) {
            return response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429;
        }
        return ModelFailureMapper.map(failure).map(mapped -> "MODEL_TEMPORARY_FAILURE".equals(mapped.code())).orElse(false);
    }

    @PreDestroy
    public void close() {
        closed.set(true);
        executor.shutdownNow();
    }
}
