package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.file.FileDataMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.FileTextMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.file.entity.AttachmentText;
import com.stonewu.agenteam.model.file.entity.CsvProfile;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 检查结果与工作完成一起保存，过期工作不能覆盖已删除的文件。
 */
@Service
public class FileInspectionTransactions {
    private final BackgroundJobMapper jobs;
    private final FileMapper files;
    private final Clock clock;
    private final FileTextMapper texts;
    private final FileDataMapper data;

    public FileInspectionTransactions(BackgroundJobMapper jobs, FileMapper files, Clock clock, FileTextMapper texts,
                                      FileDataMapper data) {
        this.jobs = jobs;
        this.files = files;
        this.clock = clock;
        this.texts = texts;
        this.data = data;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void finish(JobLease lease, FileRecord inspected, String status, String errorCode, boolean retry,
                       AttachmentText text, CsvProfile csv) {
        var now = clock.instant();
        if (!jobs.lockOwned(lease, now)) {
            return;
        }
        var file = inspected == null ? null : files.find(lease.enterpriseId(), inspected.id(), true).orElse(null);
        if (file == null || !file.status()
            .equals("scanning") || file.deletedAt() != null || (file.expiresAt() != null && !file.expiresAt()
            .isAfter(now))) {
            jobs.finish(lease, "cancelled", null, null, now, now);
            return;
        }
        if (!files.inspected(file, status, errorCode, now)) {
            return;
        }
        if (status.equals("ready") && file.purpose().equals("attachment")) {
            if (text == null) {
                throw new IllegalStateException("对话附件未解析，不能标记可用");
            }
            if (!text.text().isEmpty()) {
                texts.save(file, text, now);
            } else if (!file.mediaType().equals("application/pdf") || !"FILE_NO_TEXT".equals(errorCode)) {
                throw new IllegalStateException("只有已确认没有提取到文字的 PDF 附件可以保留空正文");
            }
        }
        if (status.equals("ready") && file.purpose().equals("data_import")) {
            if (csv == null || data.count(file) != csv.rowCount()) {
                throw new IllegalStateException("CSV 行未完整保存，不能标记可用");
            }
            data.complete(file, csv, now);
        }
        // 原文件已可用但没有文字时，文件保留原因，后台处理仍视为完成。
        String jobError = status.equals("ready") ? null : errorCode;
        jobs.finish(lease, retry ? "queued" : jobError == null ? "completed" : "failed", jobError,
            retry ? "文件检查服务暂时不可用，等待重试。" : jobError == null ? null : "文件未通过检查。",
            retry ? now.plusSeconds(60) : now, now);
    }
}
