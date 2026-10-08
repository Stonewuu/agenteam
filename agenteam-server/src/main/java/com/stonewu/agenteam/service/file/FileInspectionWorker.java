package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.file.entity.AttachmentText;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.background.BackgroundJobHeartbeat;
import com.stonewu.agenteam.service.background.BackgroundJobService;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件检查使用可恢复的数据库工作；未配置病毒扫描时继续检查，已配置的服务不可用时等待重试。
 */
@Component
public class FileInspectionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(FileInspectionWorker.class);
    private final BackgroundJobService jobs;
    private final FileMapper files;
    private final FileContentStorage storage;
    private final SkillFileValidation validation;
    private final ClamAvScanService scanner;
    private final FileInspectionTransactions transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;
    private final String worker = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newFixedThreadPool(4,
        Thread.ofVirtual().name("文件检查工作-", 0).factory());
    private final AtomicInteger active = new AtomicInteger();
    private final BackgroundJobHeartbeat heartbeats;
    private final FileTypeInspection types;
    private final AttachmentTextService attachments;
    private final CsvInspectionService csvFiles;

    public FileInspectionWorker(BackgroundJobService jobs, FileMapper files, FileContentStorage storage,
                                SkillFileValidation validation,
                                ClamAvScanService scanner, FileInspectionTransactions transactions, ObjectMapper json,
                                Clock clock,
                                @Value("${files.inspection.enabled:true}") boolean enabled,
                                BackgroundJobHeartbeat heartbeats,
                                FileTypeInspection types, AttachmentTextService attachments,
                                CsvInspectionService csvFiles) {
        this.jobs = jobs;
        this.files = files;
        this.storage = storage;
        this.validation = validation;
        this.scanner = scanner;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
        this.heartbeats = heartbeats;
        this.types = types;
        this.attachments = attachments;
        this.csvFiles = csvFiles;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!enabled) {
            return;
        }
        while (active.get() < 4) {
            var lease = jobs.claimFileInspection(worker);
            if (lease.isEmpty()) {
                return;
            }
            active.incrementAndGet();
            executor.execute(() -> {
                try {
                    inspect(lease.get());
                } finally {
                    active.decrementAndGet();
                }
            });
        }
    }

    public boolean runNext() {
        var lease = jobs.claimFileInspection(worker);
        lease.ifPresent(this::inspect);
        return lease.isPresent();
    }

    private void inspect(JobLease lease) {
        FileRecord file = null;
        try (var guard = heartbeats.start(lease)) {
            file = files.find(lease.enterpriseId(), json.readTree(lease.payloadJson()).path("fileId").asText(), false)
                .orElse(null);
            if (file == null || !file.status().equals("scanning") || (file.expiresAt() != null && !file.expiresAt()
                .isAfter(clock.instant()))) {
                transactions.finish(lease, file, "rejected", "FILE_UNAVAILABLE", false, null, null);
                return;
            }
            if (lease.exhausted()) {
                transactions.finish(lease, file, "rejected", "FILE_SCAN_UNAVAILABLE", false, null, null);
                return;
            }
            if (!guard.getAsBoolean()) {
                return;
            }
            var actual = storage.verify(file);
            if (actual.size() != file.sizeBytes() || !actual.sha256().equals(file.sha256())) {
                transactions.finish(lease, file, "rejected", "FILE_CONTENT_MISMATCH", false, null, null);
                return;
            }
            types.verify(file);
            var scanned = scanner.scan(file);
            if (scanned == ClamAvScanService.Result.UNAVAILABLE) {
                transactions.finish(lease, file, "scanning", "FILE_SCAN_UNAVAILABLE", true, null, null);
                return;
            }
            if (scanned == ClamAvScanService.Result.REJECTED) {
                transactions.finish(lease, file, "rejected", "FILE_REJECTED", false, null, null);
                return;
            }
            if (file.purpose().equals("skill_import")) {
                validation.read(file, storage);
            }
            var text = file.purpose().equals("attachment") ? attachments.parse(file, guard) : null;
            var csv = file.purpose().equals("data_import") ? csvFiles.parse(lease, file, guard) : null;
            if (guard.getAsBoolean()) {
                transactions.finish(lease, file, "ready", null, false, text, csv);
            }
        } catch (ApiException invalid) {
            LOG.warn("文件检查未完成，工作编号 {}，错误代码 {}", lease.id(), invalid.code(), invalid);
            if (file != null && file.purpose().equals("attachment") && file.mediaType().equals("application/pdf")
                && invalid.code().equals("FILE_NO_TEXT")) {
                transactions.finish(lease, file, "ready", "FILE_NO_TEXT", false, new AttachmentText("", false), null);
                return;
            }
            boolean retry = invalid.getStatusCode().is5xxServerError() || invalid.code()
                .equals("FILE_PROCESSING_CANCELLED");
            transactions.finish(lease, file, retry ? "scanning" : "rejected", invalid.code(), retry, null, null);
        } catch (Exception unavailable) {
            LOG.error("文件检查失败，工作编号 {}", lease.id(), unavailable);
            transactions.finish(lease, file, "scanning", "FILE_SCAN_UNAVAILABLE", true, null, null);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
