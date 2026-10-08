package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.file.request.FileCompleteRequest;
import com.stonewu.agenteam.model.file.request.FilePrepareRequest;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * 只在短事务中改变文件状态，上传和检查均在提交之后执行。
 */
@Service
public class FileUploadTransactions {
    private final FileMapper files;
    private final FileAccessService access;
    private final FileUploadPolicy validation;
    private final BackgroundJobMapper jobs;
    private final Clock clock;

    public FileUploadTransactions(FileMapper files, FileAccessService access, FileUploadPolicy validation,
                                  BackgroundJobMapper jobs, Clock clock) {
        this.files = files;
        this.access = access;
        this.validation = validation;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FileRecord prepare(AuthContext actor, FilePrepareRequest input) {
        String mediaType = validation.prepare(input);
        access.authorizeUpload(actor, input.purpose(), input.resourceId(), true);
        String id = UUID.randomUUID().toString();
        files.prepare(id, actor.enterpriseId(), actor.userId(), input, mediaType, clock.instant());
        return files.find(actor.enterpriseId(), id, false).orElseThrow();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FileRecord claim(AuthContext actor, String id, String lease) {
        var file = access.uploadOwner(actor, id, true);
        if (!file.status().equals("pending")) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_ALREADY_UPLOADED", "文件内容已经保存，请继续完成检查。");
        }
        if (file.uploadExpiresAt() == null || !file.uploadExpiresAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.GONE, "UPLOAD_EXPIRED", "上传地址已过期，请重新选择文件。");
        }
        if (!files.claimUpload(file, lease, clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_IN_PROGRESS", "文件正在上传，请等待当前上传结束。");
        }
        return files.find(actor.enterpriseId(), id, false).orElseThrow();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void uploaded(AuthContext actor, String id, String lease, FileContentStorage.Stored content) {
        var file = access.uploadOwner(actor, id, true);
        if (content.size() != file.expectedSizeBytes() || !content.sha256().equals(file.expectedSha256())) {
            throw mismatch();
        }
        if (!files.uploaded(file, lease, content.key(), content.size(), content.sha256(), clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_EXPIRED", "本次上传已失效，请重新读取文件状态。");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FileRecord complete(AuthContext actor, String id, FileCompleteRequest input,
                               FileContentStorage.Stored actual) {
        var file = access.uploadOwner(actor, id, true);
        if (file.sizeBytes() != actual.size() || !file.sha256()
            .equals(actual.sha256()) || input.sizeBytes() != actual.size() || !input.sha256().equals(actual.sha256())) {
            throw mismatch();
        }
        if (file.status().equals("pending")) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_UPLOADED", "请先完成文件上传。");
        }
        if (file.status().equals("uploaded")) {
            files.scanning(file, clock.instant());
            String job = UUID.randomUUID().toString();
            jobs.enqueue(job, actor.enterpriseId(), actor.userId(), "file_scan", "file_scan:" + file.id(),
                "{\"fileId\":\"" + file.id() + "\"}", clock.instant());
            jobs.setAttemptLimit(job, 1440);
        }
        return files.find(actor.enterpriseId(), file.id(), false).orElseThrow();
    }

    private static ApiException mismatch() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_CONTENT_MISMATCH",
            "上传内容与选择的文件不一致，请重新上传。");
    }
}
