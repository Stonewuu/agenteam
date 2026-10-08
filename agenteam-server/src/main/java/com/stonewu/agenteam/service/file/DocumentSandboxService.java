package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.file.parser.DocumentParserMain;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxContainerCleanup;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 文档解析与预览使用同一受限执行环境，不向解析程序传入业务凭据。
 */
@Service
public class DocumentSandboxService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentSandboxService.class);
    private static final Set<String> FORMATS = Set.of("docx", "xlsx", "pptx", "pdf", "txt", "md");
    private final SandboxExecutor executor;
    private final WorkspaceSettings settings;
    private final FileContentStorage storage;
    private final ObjectMapper json;
    private final DocumentSandboxWorkspaces workspaces;

    public DocumentSandboxService(SandboxExecutor executor, WorkspaceSettings settings, FileContentStorage storage,
                                  ObjectMapper json, SandboxContainerCleanup containers,
                                  @Value("${files.document-root:${files.root:.agenteam/files}/.documents}") String root) {
        this.executor = executor;
        this.settings = settings;
        this.storage = storage;
        this.json = json;
        this.workspaces = new DocumentSandboxWorkspaces(Path.of(root), containers);
    }

    public ParsedDocument extract(FileRecord file, BooleanSupplier current, Duration timeout) {
        String format = FileUploadPolicy.extension(file.originalName());
        DocumentSandboxWorkspaces.Lease workspace = null;
        boolean retained = false;
        try {
            try (var input = storage.open(file)) {
                workspace = execute(input, format, "extract", current, timeout, file);
            }
            Path output = workspace.directory().resolve("result/outputs/document");
            if (Files.size(output.resolve("result.json")) > 65536) {
                throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
            }
            var status = json.readValue(output.resolve("result.json").toFile(), DocumentParserMain.Result.class);
            if (!status.success()) {
                throw DocumentParserProcess.failure(status.errorCode());
            }
            if (status.chunkCount() < 1 || status.chunkCount() > 200000) {
                throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
            }
            retained = true;
            return new ParsedDocument(output, json, status, workspace::close);
        } catch (IOException failure) {
            throw FileStorageKeys.storageUnavailable(failure);
        } finally {
            if (workspace != null && !retained) {
                workspace.close();
            }
        }
    }

    public void render(InputStream source, String format, Path target, BooleanSupplier current) {
        try (var workspace = execute(source, format, "render", current, Duration.ofSeconds(180), null)) {
            Path rendered = workspace.directory().resolve("result/outputs/document/source.pdf");
            if (!Files.isRegularFile(rendered, LinkOption.NOFOLLOW_LINKS) || Files.size(rendered) == 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OFFICE_PREVIEW_FAILED",
                    "此文件暂时无法生成预览，可下载原文件查看。");
            }
            Files.copy(rendered, target);
        } catch (IOException failure) {
            throw FileStorageKeys.storageUnavailable(failure);
        }
    }

    private DocumentSandboxWorkspaces.Lease execute(InputStream input, String format, String operation,
                                                    BooleanSupplier current, Duration timeout, FileRecord expected) {
        if (!FORMATS.contains(format) || operation.equals("render") && !Set.of("docx", "xlsx", "pptx")
            .contains(format)) {
            throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
        }
        DocumentSandboxWorkspaces.Lease workspace = null;
        boolean retained = false;
        String invocation = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (var control = new ToolCallControl(() -> {
            if (!current.getAsBoolean()) {
                throw DocumentParserProcess.failure("FILE_PROCESSING_CANCELLED");
            }
        }); var watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("文档处理取消检查").factory())) {
            watchdog.scheduleWithFixedDelay(() -> {
                try {
                    if (!current.getAsBoolean() || System.nanoTime() >= deadline) {
                        control.close();
                    }
                } catch (RuntimeException failure) {
                    LOG.warn("检查文档处理是否仍有效失败，调用编号 {}", invocation, failure);
                    control.close();
                }
            }, 0, 100, TimeUnit.MILLISECONDS);
            workspace = workspaces.create();
            Path directory = workspace.directory();
            Path source = directory.resolve("source");
            for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
                Files.createDirectories(source.resolve(area));
            }
            try (var output = Files.newOutputStream(source.resolve("inputs/source." + format))) {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    control.requireActive();
                    total += count;
                    if (total > settings.fileBytes()) {
                        throw DocumentParserProcess.failure("FILE_TOO_LARGE");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                if (expected != null && (total != expected.sizeBytes() || !HexFormat.of().formatHex(digest.digest())
                    .equals(expected.sha256()))) {
                    throw DocumentParserProcess.failure("FILE_CONTENT_MISMATCH");
                }
            }
            String command = "agenteam-office " + operation + " /workspace/inputs/source." + format
                + (operation.equals(
                "extract") ? " --format " + format : "") + " --output-dir /workspace/outputs/document";
            var result = executor.execute(source, directory.resolve("result"), directory, invocation, command, "work",
                Duration.ofNanos(Math.max(1, deadline - System.nanoTime())), control);
            if (result.timedOut()) {
                throw DocumentParserProcess.failure("FILE_PROCESSING_TIMEOUT");
            }
            if (result.capacityExceeded()) {
                throw DocumentParserProcess.failure("FILE_EXPANDED_TOO_LARGE");
            }
            if (operation.equals("extract") && !Files.isRegularFile(
                directory.resolve("result/outputs/document/result.json"))) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_RUNTIME_UNAVAILABLE",
                    "文档处理环境暂不可用，请稍后重试。");
            }
            retained = true;
            return workspace;
        } catch (IOException failure) {
            throw FileStorageKeys.storageUnavailable(failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行环境缺少文件摘要算法", failure);
        } finally {
            if (workspace != null && !retained) {
                workspace.close();
            }
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void cleanupUnused() {
        workspaces.cleanupUnused();
    }
}
