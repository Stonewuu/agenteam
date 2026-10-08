package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.file.FileDataMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.file.entity.CsvRow;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 解析中断时保留不可用状态，新工作清理未完成行后重新解析。
 */
@Service
public class CsvInspectionTransactions {
    private final BackgroundJobMapper jobs;
    private final FileMapper files;
    private final FileDataMapper data;
    private final Clock clock;

    public CsvInspectionTransactions(BackgroundJobMapper jobs, FileMapper files, FileDataMapper data, Clock clock) {
        this.jobs = jobs;
        this.files = files;
        this.data = data;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void start(JobLease lease, FileRecord file) {
        data.clear(owned(lease, file));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void append(JobLease lease, FileRecord file, List<CsvRow> batch) {
        if (batch.isEmpty() || batch.size() > 100) {
            throw new IllegalArgumentException("CSV 写入批次不正确");
        }
        data.append(owned(lease, file), batch);
    }

    private FileRecord owned(JobLease lease, FileRecord file) {
        if (!jobs.lockOwned(lease, clock.instant())) {
            throw DocumentParserProcess.failure("FILE_PROCESSING_CANCELLED");
        }
        var current = files.find(lease.enterpriseId(), file.id(), true).orElseThrow(FileAccessService::unavailable);
        if (!current.purpose().equals("data_import") || !current.status().equals("scanning") || !current.sha256()
            .equals(file.sha256())
            || current.deletedAt() != null || (current.expiresAt() != null && !current.expiresAt()
            .isAfter(clock.instant()))) {
            throw FileAccessService.unavailable();
        }
        return current;
    }
}
