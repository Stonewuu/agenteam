package com.stonewu.agenteam.service.modelusage;

import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 有界保存队列只保留数值；数据库故障时不重发模型，也不无限积累内存。
 */
@Component
public class ModelUsageJournal {
    private static final Logger LOG = LoggerFactory.getLogger(ModelUsageJournal.class);
    private final ModelUsageService service;
    private final Clock clock;
    private final String owner = UUID.randomUUID().toString();
    private final Semaphore capacity;
    private final ConcurrentHashMap<String, Call> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ModelUsageSnapshot> pending = new ConcurrentHashMap<>();
    private int scanStart;

    @Autowired
    public ModelUsageJournal(ModelUsageService service, Clock clock) {
        this(service, clock, 256);
    }

    ModelUsageJournal(ModelUsageService service, Clock clock, int capacity) {
        this.service = service;
        this.clock = clock;
        this.capacity = new Semaphore(capacity);
    }

    public Call open(ModelCallContext context, ModelProfileRecord model, ModelProviderRecord provider) {
        if (context == null || !model.enterpriseId().equals(context.enterpriseId())) {
            throw new IllegalStateException("模型调用缺少可信的运行归属。");
        }
        if (!capacity.tryAcquire()) {
            throw new IllegalStateException("模型用量保存正在恢复，请稍后重试。");
        }
        return new Call(context, model, provider);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void flush() {
        int processed = 0;
        for (var value : pending.values()) {
            if (processed++ >= 100) {
                break;
            }
            try {
                service.save(value);
                if (pending.remove(value.callId(), value)) {
                    capacity.release();
                }
            } catch (RuntimeException failure) {
                LOG.warn("补存模型用量失败，企业={}，调用={}", value.enterpriseId(), value.callId(), failure);
                break;
            }
        }
        var calls = new ArrayList<>(active.values());
        for (int index = 0; index < Math.min(100, calls.size()); index++) {
            var call = calls.get((scanStart + index) % calls.size());
            try {
                call.checkpoint();
            } catch (RuntimeException failure) {
                LOG.warn("保存模型用量快照失败，企业={}，调用={}", call.context.enterpriseId(), call.id, failure);
            }
        }
        scanStart = calls.isEmpty() ? 0 : (scanStart + 100) % calls.size();
    }

    public final class Call {
        private final String id = UUID.randomUUID().toString();
        private final ModelCallContext context;
        private final ModelProfileRecord model;
        private final ModelProviderRecord provider;
        private final ModelUsageAccumulator usage;
        private boolean registered;
        private boolean finished;
        private long savedSequence;
        private Integer httpStatus;
        private String requestId;
        private String errorCode;
        private String errorCategory;

        private Call(ModelCallContext context, ModelProfileRecord model, ModelProviderRecord provider) {
            this.context = context;
            this.model = model;
            this.provider = provider;
            var options = model.capabilities().protocolOptions();
            this.usage = new ModelUsageAccumulator(provider.protocol(), options.usageFormat(), Set.copyOf(options.notApplicableUsage()));
        }

        public String id() {
            return id;
        }

        public ModelUsageAccumulator usage() {
            return usage;
        }

        public synchronized void beforeDispatch() {
            if (finished) {
                throw new CancellationException("模型请求已经取消。");
            }
            if (registered) {
                throw new IllegalStateException("模型请求不能重复登记或发送。");
            }
            service.register(id, owner, context, model, provider);
            registered = true;
            active.put(id, this);
        }

        public synchronized void diagnostics(Integer status, String trace, String code, String category) {
            httpStatus = status;
            requestId = trace;
            errorCode = code;
            errorCategory = category;
        }

        private synchronized void checkpoint() {
            if (!registered || finished) {
                return;
            }
            var value = snapshot("in_flight");
            if (value.sequence() > savedSequence) {
                service.save(value);
                savedSequence = value.sequence();
            }
            service.heartbeat(context.enterpriseId(), id, owner);
        }

        public synchronized void finish(String status) {
            if (finished) {
                return;
            }
            finished = true;
            active.remove(id);
            if (!registered) {
                capacity.release();
                return;
            }
            var value = snapshot(status);
            try {
                service.save(value);
                capacity.release();
            } catch (RuntimeException failure) {
                // open 时已经预留一个位置；结束快照不会因队列已满而被丢弃。
                pending.put(id, value);
                LOG.warn("模型调用已结束，用量等待补存，企业={}，调用={}", context.enterpriseId(), id, failure);
            }
        }

        private ModelUsageSnapshot snapshot(String status) {
            return new ModelUsageSnapshot(context.enterpriseId(), id, owner, usage.snapshot(), status,
                clock.instant(), httpStatus, requestId, errorCode, errorCategory);
        }
    }
}
