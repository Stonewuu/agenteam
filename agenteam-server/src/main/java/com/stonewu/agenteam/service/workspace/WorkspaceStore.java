package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 工作文件按对话保存；修改先写独立目录，仍有执行资格时才替换当前版本。
 */
@Component
public class WorkspaceStore {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceStore.class);
    private final Path root;
    private final WorkspaceSettings settings;
    private final ObjectMapper json;

    public WorkspaceStore(WorkspaceSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        root = Path.of(settings.root()).toAbsolutePath().normalize();
    }

    public record Manifest(String version, String enterpriseId, String userId, String conversationId, String sessionId,
                           String runId, long leaseVersion, Set<String> sourceResultIds, Set<String> inputFileIds,
                           long savedAt) {
    }

    public Path root() {
        return root;
    }

    public Path directory(RunRecord run, String session) {
        return directory(run.enterpriseId(), run.userId(), run.conversationId(), session);
    }

    public Path directory(String enterprise, String user, String conversation, String session) {
        return root.resolve(EncryptedAgentStateStore.component(enterprise))
            .resolve(EncryptedAgentStateStore.component(user))
            .resolve(EncryptedAgentStateStore.component(conversation))
            .resolve(EncryptedAgentStateStore.component(session));
    }

    public record Snapshot(Manifest manifest, WorkspaceFilesystem files, boolean cleared) {
    }

    /**
     * 浏览只读取原子发布的版本，不等待命令执行期间持有的写锁。
     */
    public Snapshot snapshot(String enterprise, String user, String conversation) {
        Path directory = directory(enterprise, user, conversation, conversation);
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return new Snapshot(null, null, false);
            }
            WorkspacePaths.requireInside(root, directory);
            for (int attempt = 0; attempt < 2; attempt++) {
                var manifest = load(enterprise, user, conversation, conversation, directory);
                if (manifest.version() == null) {
                    return new Snapshot(manifest, null,
                        Files.isRegularFile(directory.resolve("cleared.json"), LinkOption.NOFOLLOW_LINKS));
                }
                Path version = directory.resolve("versions").resolve(manifest.version());
                WorkspacePaths.requireInside(root, version);
                if (Files.isDirectory(version, LinkOption.NOFOLLOW_LINKS)) {
                    return new Snapshot(manifest, new WorkspaceFilesystem(version, settings, () -> {
                    }, false), false);
                }
                // 发布新版本后会清理旧目录，再读一次指针可避开这一瞬间的目录切换。
            }
            throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_FILES_MISSING",
                "此对话的工作文件已不可读取，请刷新后重试。");
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    public Scope open(RunRecord run, String session, ToolCallControl control, Duration timeout, Runnable requireLease) {
        requireLease.run();
        control.requireActive();
        Path directory = directory(run, session);
        FileChannel channel = null;
        FileLock lock = null;
        try {
            Files.createDirectories(root);
            WorkspacePaths.requireInside(root, directory);
            Files.createDirectories(directory);
            Path lockFile = directory.resolve(".lock");
            WorkspacePaths.requireInside(root, lockFile);
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (lock == null) {
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    // 同一进程中的另一个文件操作尚未完成。
                }
                if (lock != null) {
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_BUSY",
                        "工作文件仍被上一个操作占用，请稍后重试。");
                }
                control.pause(Duration.ofMillis(50));
            }
            control.requireActive();
            requireLease.run();
            Files.setLastModifiedTime(directory, FileTime.from(Instant.now()));
            return new Scope(run, session, directory, channel, lock, control, requireLease,
                load(run, session, directory), deadline);
        } catch (IOException | RuntimeException failure) {
            try {
                if (lock != null) {
                    lock.close();
                }
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            if (failure instanceof IOException cause) {
                throw WorkspacePaths.io(cause);
            }
            throw (RuntimeException) failure;
        }
    }

    private Manifest load(RunRecord run, String session, Path directory) throws IOException {
        return load(run.enterpriseId(), run.userId(), run.conversationId(), session, directory);
    }

    private Manifest load(String enterprise, String user, String conversation, String session,
                          Path directory) throws IOException {
        Path pointer = directory.resolve("current.json");
        WorkspacePaths.requireInside(root, pointer);
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return new Manifest(null, enterprise, user, conversation, session, null, 0, Set.of(), Set.of(), 0);
        }
        if (Files.size(pointer) > 2 * 1024 * 1024) {
            throw WorkspacePaths.capacity();
        }
        Manifest saved = json.readValue(Files.readAllBytes(pointer), Manifest.class);
        if (!enterprise.equals(saved.enterpriseId()) || !user.equals(saved.userId())
            || !conversation.equals(saved.conversationId()) || !session.equals(saved.sessionId())
            || saved.version() == null || !saved.version()
            .matches("[0-9a-f-]{36}") || saved.sourceResultIds() == null || saved.inputFileIds() == null) {
            throw WorkspacePaths.invalid();
        }
        return saved;
    }

    public final class Scope implements WorkspaceSession {
        private final RunRecord run;
        private final String session;
        private final Path directory;
        private final FileChannel channel;
        private final FileLock lock;
        private final ToolCallControl control;
        private final Runnable requireLease;
        private Manifest manifest;
        private Path pending;
        private final long deadline;

        private Scope(RunRecord run, String session, Path directory, FileChannel channel, FileLock lock,
                      ToolCallControl control, Runnable requireLease, Manifest manifest, long deadline) {
            this.run = run;
            this.session = session;
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
            this.control = control;
            this.requireLease = requireLease;
            this.manifest = manifest;
            this.deadline = deadline;
        }

        public Manifest manifest() {
            return manifest;
        }

        public Path directory() {
            return directory;
        }

        public boolean cleared() {
            return manifest.version() == null && Files.isRegularFile(directory.resolve("cleared.json"),
                LinkOption.NOFOLLOW_LINKS);
        }

        private void requireActive() {
            remaining();
        }

        public Duration remaining() {
            control.requireActive();
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "WORKSPACE_TIMEOUT",
                    "本次文件操作超过允许时间，请缩小处理范围。");
            }
            return Duration.ofNanos(nanos);
        }

        public WorkspaceFilesystem current() {
            Path path = manifest.version() == null ? directory.resolve("empty") : directory.resolve("versions")
                .resolve(manifest.version());
            try {
                WorkspacePaths.requireInside(root, path);
                if (manifest.version() == null) {
                    initialize(path);
                } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_FILES_MISSING",
                        "本次对话的工作文件已不可读取，请重新提供需要处理的文件。");
                }
            } catch (IOException cause) {
                throw WorkspacePaths.io(cause);
            }
            return new WorkspaceFilesystem(path, settings, this::requireActive, false);
        }

        public WorkspaceFilesystem prepare() {
            if (pending != null) {
                throw new IllegalStateException("当前操作已经准备了工作文件");
            }
            Path source = current().root();
            current().validate();
            pending = directory.resolve("versions").resolve(UUID.randomUUID().toString());
            try {
                WorkspacePaths.requireInside(root, pending);
                initialize(pending);
                copyTree(source, pending, control);
            } catch (IOException cause) {
                throw WorkspacePaths.io(cause);
            }
            return new WorkspaceFilesystem(pending, settings, this::requireActive, true);
        }

        public void commit(Set<String> sources, Set<String> inputs) {
            if (pending == null) {
                throw new IllegalStateException("尚未准备工作文件");
            }
            new WorkspaceFilesystem(pending, settings, this::requireActive, true).validate();
            if (sources.size() > 10000 || inputs.size() > settings.maximumFiles()) {
                throw WorkspacePaths.capacity();
            }
            requireActive();
            requireLease.run();
            var next = new Manifest(pending.getFileName().toString(), run.enterpriseId(), run.userId(),
                run.conversationId(), session,
                run.id(), run.leaseVersion(), Set.copyOf(sources), Set.copyOf(inputs), System.currentTimeMillis());
            Path temporary = directory.resolve("current-" + UUID.randomUUID() + ".json");
            try {
                Files.write(temporary, json.writeValueAsBytes(next), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                requireActive();
                requireLease.run();
                Files.move(temporary, directory.resolve("current.json"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
                manifest = next;
                pending = null;
                cleanupVersions();
            } catch (IOException cause) {
                throw WorkspacePaths.io(cause);
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cause) {
                    LOG.warn("工作文件临时记录清理失败，执行编号 {}", run.id(), cause);
                }
            }
        }

        private void cleanupVersions() {
            Path versions = directory.resolve("versions");
            try (var paths = Files.list(versions)) {
                for (var path : paths.toList()) {
                    if (!path.getFileName().toString().equals(manifest.version())) {
                        deleteTree(directory, path);
                    }
                }
            } catch (IOException cause) {
                LOG.warn("旧工作文件清理失败，执行编号 {}", run.id(), cause);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                if (pending != null) {
                    deleteTree(directory, pending);
                }
            } finally {
                try {
                    lock.close();
                } finally {
                    channel.close();
                }
            }
        }
    }

    private static void initialize(Path directory) throws IOException {
        Files.createDirectories(directory);
        for (String child : List.of("inputs", "tool-results", "work", "outputs")) {
            Files.createDirectories(directory.resolve(child));
        }
    }

    private static void copyTree(Path source, Path target, ToolCallControl control) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                                                     BasicFileAttributes attributes) throws IOException {
                control.requireActive();
                WorkspacePaths.requireInside(source, directory);
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                control.requireActive();
                WorkspacePaths.requireInside(source, file);
                if (!attributes.isRegularFile()) {
                    throw WorkspacePaths.invalid();
                }
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 清理只接受已确认属于指定私有目录的子路径，目录链接本身删除但绝不跟随。
     */
    public static void deleteTree(Path parent, Path target) throws IOException {
        Path base = parent.toAbsolutePath().normalize(), path = target.toAbsolutePath().normalize();
        if (path.equals(base) || !path.startsWith(base)) {
            throw WorkspacePaths.invalid();
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        WorkspacePaths.requireInside(base, path.getParent());
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
