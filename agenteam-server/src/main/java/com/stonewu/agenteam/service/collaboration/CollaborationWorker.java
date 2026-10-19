package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** 调度、执行观察和取消各自使用短事务；用户无需保持网页连接。 */
@Component
public class CollaborationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(CollaborationWorker.class);
    private final CollaborationDispatchService dispatch;
    private final CollaborationRunObserver observer;
    private final CollaborationRecoveryService recovery;
    private final RunLifecycleService lifecycle;
    private final ChatConfiguration chat;
    private final String owner = UUID.randomUUID().toString();
    private final CollaborationGeneratedResultService generated;

    public CollaborationWorker(CollaborationDispatchService dispatch, CollaborationRunObserver observer,
            CollaborationRecoveryService recovery, RunLifecycleService lifecycle, ChatConfiguration chat,
            CollaborationGeneratedResultService generated) {
        this.dispatch = dispatch;
        this.observer = observer;
        this.recovery = recovery;
        this.lifecycle = lifecycle;
        this.chat = chat;
        this.generated = generated;
    }

    @Scheduled(scheduler = "chatBackgroundScheduler", fixedDelayString = "${collaboration.poll-interval-ms:2000}")
    public void poll() {
        if (!chat.isWorkerEnabled()) {
            return;
        }
        try {
            recover();
            if (!chat.isEnabled()) {
                return;
            }
            for (int i = 0; i < Math.min(50, chat.getBatchSize()); i++) {
                if (!dispatchOnce()) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("协作后台工作本轮未能完成", new ChannelProviderException("COLLABORATION_WORKER_FAILED", "后台工作稍后继续。", failure));
        }
    }

    public boolean dispatchOnce() {
        var lease = dispatch.claim(owner);
        if (lease.isEmpty()) {
            return false;
        }
        try {
            dispatch.submit(lease.get());
        } catch (RuntimeException failure) {
            String code = failure instanceof ApiException api ? api.code() : "COLLABORATION_SUBMISSION_FAILED";
            LOG.warn("协作工作尚未提交，企业={}，工作={}", lease.get().enterpriseId(), lease.get().workId(),
                new ChannelProviderException(code, "岗位工作暂时无法开始。", failure));
            dispatch.failed(lease.get(), code, Set.of("CONCURRENCY_LIMIT", "CONVERSATION_BUSY", "COLLABORATION_SUBMISSION_FAILED").contains(code));
        }
        return true;
    }

    public void recover() {
        for (var task : recovery.ending()) {
            recovery.settle(task.getEnterpriseId(), task.getId());
        }
        for (var link : observer.pending()) {
            try {
                var stop = recovery.recheck(link.getEnterpriseId(), link.getId());
                if (stop.isPresent()) {
                    lifecycle.cancelCollaboration(stop.get().enterpriseId(), stop.get().runId(), stop.get().taskId());
                }
                observer.observe(link.getEnterpriseId(), link.getId(), generated.prepare(link));
            } catch (RuntimeException failure) {
                LOG.warn("协作执行状态尚未归并，企业={}，执行={}", link.getEnterpriseId(), link.getRunId(),
                    new ChannelProviderException("COLLABORATION_OBSERVATION_FAILED", "岗位执行状态稍后继续处理。", failure));
                if (failure instanceof ApiException api) {
                    observer.failedObservation(link.getEnterpriseId(), link.getId(), api.code());
                }
            }
        }
    }
}
