package com.stonewu.agenteam.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.file.entity.DocumentChunk;
import com.stonewu.agenteam.service.background.BackgroundJobHeartbeat;
import com.stonewu.agenteam.service.background.BackgroundJobService;
import com.stonewu.agenteam.service.file.DocumentParserProcess;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件解析在数据库事务外完成，逐批提交的文字在完成前不可检索。
 */
@Component
public class KnowledgeProcessingWorker {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeProcessingWorker.class);
    private final BackgroundJobService jobs;
    private final BackgroundJobHeartbeat heartbeats;
    private final KnowledgeProcessingTransactions transactions;
    private final DocumentParserProcess parser;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String worker = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newFixedThreadPool(4,
        Thread.ofVirtual().name("知识资料处理-", 0).factory());
    private final AtomicInteger active = new AtomicInteger();

    public KnowledgeProcessingWorker(BackgroundJobService jobs, BackgroundJobHeartbeat heartbeats,
                                     KnowledgeProcessingTransactions transactions,
                                     DocumentParserProcess parser, ObjectMapper json,
                                     @Value("${knowledge.processing.enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.heartbeats = heartbeats;
        this.transactions = transactions;
        this.parser = parser;
        this.json = json;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!enabled) {
            return;
        }
        while (active.get() < 4) {
            var lease = jobs.claimKnowledgeProcessing(worker);
            if (lease.isEmpty()) {
                return;
            }
            active.incrementAndGet();
            executor.execute(() -> {
                try {
                    process(lease.get());
                } finally {
                    active.decrementAndGet();
                }
            });
        }
    }

    public boolean runNext() {
        var lease = jobs.claimKnowledgeProcessing(worker);
        lease.ifPresent(this::process);
        return lease.isPresent();
    }

    private void process(JobLease lease) {
        String id = "";
        int generation = 0;
        try (var guard = heartbeats.start(lease)) {
            var payload = json.readTree(lease.payloadJson());
            id = payload.path("documentId").asText();
            generation = payload.path("generation").asInt();
            var source = transactions.start(lease, id, generation);
            String documentId = id;
            int target = generation;
            try (var parsed = parser.parse(source, guard)) {
                List<DocumentChunk> batch = new ArrayList<>();
                parsed.forEach(chunk -> {
                    if (!guard.getAsBoolean()) {
                        throw DocumentParserProcess.failure("FILE_PROCESSING_CANCELLED");
                    }
                    batch.add(chunk);
                    if (batch.size() == 100) {
                        transactions.append(lease, documentId, target, List.copyOf(batch));
                        batch.clear();
                    }
                });
                if (!batch.isEmpty()) {
                    transactions.append(lease, id, generation, batch);
                }
                if (guard.getAsBoolean()) {
                    transactions.complete(lease, id, generation, parsed.chunkCount(), parsed.pageCount());
                }
            }
        } catch (ApiException failed) {
            LOG.warn("知识资料处理未完成，工作编号 {}，文档编号 {}，错误代码 {}", lease.id(), id, failed.code(), failed);
            boolean retry = failed.getStatusCode().is5xxServerError();
            transactions.failed(lease, id, generation, failed.code(),
                retry ? "文件服务暂时不可用，等待重试。" : failed.getReason(), retry);
        } catch (ResponseStatusException denied) {
            LOG.warn("知识资料处理被拒绝，工作编号 {}，文档编号 {}", lease.id(), id, denied);
            transactions.failed(lease, id, generation, "KNOWLEDGE_ACCESS_REVOKED", "处理发起人已无法编辑这份资料。",
                false);
        } catch (Exception unavailable) {
            LOG.error("知识资料处理失败，工作编号 {}，文档编号 {}", lease.id(), id, unavailable);
            transactions.failed(lease, id, generation, "KNOWLEDGE_PROCESSING_UNAVAILABLE",
                "资料暂时无法完成处理，等待重试。", true);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
