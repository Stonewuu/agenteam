package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.service.workspace.SandboxContainerCleanup;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件锁覆盖转换和解析结果读取，清理失败时保留容器记录供后续重试。
 */
final class DocumentSandboxWorkspaces {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentSandboxWorkspaces.class);
    private final Path root;
    private final SandboxContainerCleanup containers;

    DocumentSandboxWorkspaces(Path root, SandboxContainerCleanup containers) {
        this.root = root.toAbsolutePath().normalize();
        this.containers = containers;
    }

    final class Lease implements AutoCloseable {
        private final Path directory;
        private final FileChannel channel;
        private final FileLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Path directory, FileChannel channel, FileLock lock) {
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
        }

        Path directory() {
            return directory;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                cleanup(this);
            }
        }
    }

    Lease create() throws IOException {
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "document-");
        var channel = FileChannel.open(directory.resolve(".active.lock"), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
        try {
            return new Lease(directory, channel, channel.lock());
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void cleanupUnused() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant before = Instant.now().minus(Duration.ofMinutes(5));
        try (var entries = Files.list(root)) {
            for (Path directory : entries.toList()) {
                if (!directory.getFileName().toString().startsWith("document-") || !Files.isDirectory(directory,
                    LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(directory).toInstant().isAfter(before)) {
                        continue;
                    }
                    try (var channel = FileChannel.open(directory.resolve(".active.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        FileLock lock;
                        try {
                            lock = channel.tryLock();
                        } catch (OverlappingFileLockException active) {
                            continue;
                        }
                        if (lock != null) {
                            new Lease(directory, channel, lock).close();
                        }
                    }
                } catch (NoSuchFileException removed) {
                    // 另一个实例已完成清理，无需重复处理。
                } catch (IOException failure) {
                    LOG.warn("检查文档临时目录失败，目录编号 {}", directory.getFileName(), failure);
                }
            }
        } catch (IOException failure) {
            LOG.warn("读取文档临时目录失败，工作编号 document-cleanup", failure);
        }
    }

    private void cleanup(Lease lease) {
        try (var channel = lease.channel; var lock = lease.lock) {
            Path pending = lease.directory.resolve("sandbox.pending");
            if (Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS)) {
                containers.removeForWorkspace(Files.readString(pending).trim());
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warn("文档容器清理未完成，保留目录等待重试，目录编号 {}", lease.directory.getFileName(), failure);
            return;
        }
        try {
            WorkspaceStore.deleteTree(root, lease.directory);
        } catch (IOException failure) {
            LOG.warn("文档临时文件清理失败，目录编号 {}", lease.directory.getFileName(), failure);
        }
    }
}
