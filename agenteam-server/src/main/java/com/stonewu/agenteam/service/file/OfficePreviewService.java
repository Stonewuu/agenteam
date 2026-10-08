package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.file.FilePreviewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.OpenedPreviewFile;
import com.stonewu.agenteam.model.file.response.ConversationFileView;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 预览始终经原文件授权；转换结果按文件版本缓存，不覆盖原文档。
 */
@Service
public class OfficePreviewService {
    private static final Logger LOG = LoggerFactory.getLogger(OfficePreviewService.class);
    private final ConversationFileService files;
    private final DocumentSandboxService documents;
    private final Path root;
    private final AccountBehaviorService behavior;

    public OfficePreviewService(ConversationFileService files, DocumentSandboxService documents,
                                @Value("${files.preview-root:${files.root:.agenteam/files}/.previews}") String root,
                                AccountBehaviorService behavior) {
        this.files = files;
        this.documents = documents;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.behavior = behavior;
    }

    public ConversationFileView prepare(AuthContext actor, String conversation, String id) {
        try (var preview = open(actor, conversation, id)) {
            return preview.file();
        } catch (IOException failure) {
            throw FileStorageKeys.storageUnavailable(failure);
        }
    }

    public OpenedPreviewFile open(AuthContext actor, String conversation, String id) {
        Path root = behavior.workspaceRoot(this.root, actor.userId());
        try (var original = files.open(actor, conversation, id)) {
            var file = original.file();
            String format = FileUploadPolicy.extension(file.name());
            if (!Set.of("docx", "xlsx", "pptx").contains(format)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OFFICE_PREVIEW_UNSUPPORTED",
                    "此格式暂不支持在线预览，可下载原文件查看。");
            }
            String key = Utf8Text.revision("office-preview-v1",
                String.join("\n", actor.enterpriseId(), actor.userId(), conversation, id, file.revision()));
            Files.createDirectories(root);
            Path target = root.resolve(key + ".pdf");
            WorkspacePaths.requireInside(root, target);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                convert(original, format, target, key, root);
            }
            files.metadata(actor, conversation, id);
            var metadata = FilePreviewMapper.view(id, file.name() + ".pdf", file.path(), false, file.source(),
                "application/pdf",
                Files.size(target), file.modifiedAt(), key);
            return new OpenedPreviewFile(metadata, Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException failure) {
            throw FileStorageKeys.storageUnavailable(failure);
        }
    }

    private void convert(OpenedPreviewFile original, String format, Path target, String key,
                         Path root) throws IOException {
        Path lockPath = root.resolve("lock-" + key.substring(0, 2));
        WorkspacePaths.requireInside(root, lockPath);
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(200).toNanos();
            while (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock = null;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException waiting) {
                    // 同一实例已在生成此版本，继续等待其结果。
                }
                if (lock != null) {
                    try (var acquired = lock) {
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                            Path pending = root.resolve(key + "-" + UUID.randomUUID() + ".tmp");
                            try {
                                documents.render(original.input(), format, pending,
                                    () -> !Thread.currentThread().isInterrupted());
                                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
                            } finally {
                                Files.deleteIfExists(pending);
                            }
                        }
                    }
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "OFFICE_PREVIEW_TIMEOUT",
                        "文档预览生成超时，请稍后重试或下载原文件。");
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("文档预览请求已经取消", failure);
                }
            }
        }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void cleanup() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant before = Instant.now().minus(Duration.ofDays(7));
        try (var entries = Files.list(root)) {
            for (Path path : entries.filter(value -> value.getFileName().toString()
                .matches("[0-9a-f]{64}(?:-[0-9a-f-]{36})?\\.(pdf|tmp)")).toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(path).toInstant()
                    .isBefore(before)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException failure) {
            LOG.warn("清理文档预览缓存失败，工作编号 office-preview-cleanup", failure);
        }
    }
}
