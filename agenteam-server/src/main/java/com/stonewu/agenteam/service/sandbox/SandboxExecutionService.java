package com.stonewu.agenteam.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.sandbox.SandboxArchiveMapper;
import com.stonewu.agenteam.model.sandbox.request.ProjectExecutionRequest;
import com.stonewu.agenteam.model.sandbox.request.SandboxExecutionRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectCommandExecutor;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 独立节点只管理临时任务文件；持久工作文件仍由调用方确认后保存。
 */
public final class SandboxExecutionService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SandboxExecutionService.class);
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final Map<String, Long> cancelled = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final SandboxExecutor executor;
    private final SandboxArchiveMapper archives;
    private final WorkspaceSettings settings;
    private final Path root;
    private final int maximum;
    private final FileChannel ownership;
    private final FileLock owner;
    private final ObjectMapper json;

    private record Active(ToolCallControl control, Path directory, long deadline) {
    }

    public SandboxExecutionService(SandboxExecutor executor, WorkspaceSettings settings, ObjectMapper json, Path root,
                                   int maximum) throws IOException {
        this.executor = executor;
        this.settings = settings;
        this.json = json;
        this.archives = new SandboxArchiveMapper(json, settings);
        this.root = root.toAbsolutePath().normalize();
        this.maximum = maximum;
        Files.createDirectories(this.root);
        ownership = FileChannel.open(this.root.resolve(".owner.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
        try {
            owner = ownership.tryLock();
        } catch (IOException | RuntimeException failure) {
            try {
                ownership.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (owner == null) {
            ownership.close();
            throw new IOException("独立执行目录已被另一个进程使用");
        }
        try {
            recoverAbandoned();
        } catch (IOException | RuntimeException failure) {
            owner.close();
            ownership.close();
            throw failure;
        }
        watchdog.scheduleWithFixedDelay(() -> {
            active.values().forEach(job -> {
                if (System.nanoTime() >= job.deadline()) {
                    job.control().close();
                }
            });
            cancelled.entrySet().removeIf(entry -> entry.getValue() < System.nanoTime());
        }, 1, 1, TimeUnit.SECONDS);
    }

    public final class ResultArchive implements AutoCloseable {
        private final String id;
        private final Path path;

        private ResultArchive(String id, Path path) {
            this.id = id;
            this.path = path;
        }

        public Path path() {
            return path;
        }

        @Override
        public void close() {
            release(id);
        }
    }

    public ResultArchive execute(String id, InputStream input) throws IOException {
        Active job = acquire(id);
        boolean complete = false;
        try {
            Path source = job.directory().resolve("source");
            var request = archives.read(job.control().track(input), source,
                Set.of("work", "outputs", "inputs", "tool-results"),
                SandboxExecutionRequest.class, job.control());
            if (!settings.network().equals(request.networkPolicy())) {
                throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_NETWORK_MISMATCH",
                    "调用方与执行节点的联网设置不一致");
            }
            if (request.timeoutMillis() < 1 || request.timeoutMillis() > settings.timeoutSeconds() * 1000L) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SANDBOX_TIMEOUT_INVALID", "执行时间超过节点允许范围");
            }
            Path output = job.directory().resolve("result");
            long remaining = Math.min(request.timeoutMillis(),
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(job.deadline() - System.nanoTime())));
            var result = executor.execute(source, output, job.directory(), request.callId(), request.command(),
                request.workingDirectory(),
                Duration.ofMillis(remaining), job.control());
            Path packed = job.directory().resolve("result.zip");
            try (var stream = Files.newOutputStream(packed)) {
                archives.write(stream, result, output, Set.of("work", "outputs"), job.control());
            }
            complete = true;
            return new ResultArchive(id, packed);
        } finally {
            if (!complete) {
                release(id);
            }
        }
    }

    public SandboxExecutor.Result executeProject(String id, InputStream input,
                                                 ProjectCommandExecutor projects) throws IOException {
        var job = acquire(id);
        try {
            byte[] body = job.control().track(input).readNBytes(131073);
            if (body.length > 131072) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SANDBOX_REQUEST_TOO_LARGE",
                    "执行请求超过允许范围");
            }
            var request = json.readValue(body, ProjectExecutionRequest.class);
            if (!settings.network().equals(request.networkPolicy())) {
                throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_NETWORK_MISMATCH",
                    "调用方与执行节点的联网设置不一致");
            }
            if (request.project() == null || request.timeoutMillis() < 1 || request.timeoutMillis() > settings.timeoutSeconds() * 1000L) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SANDBOX_REQUEST_INVALID", "项目或执行时间不正确");
            }
            var timeout = Duration.ofMillis(Math.min(request.timeoutMillis(),
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(job.deadline() - System.nanoTime()))));
            if (request.recoverOnly()) {
                projects.recover(request.project(), timeout, job.control());
                return new SandboxExecutor.Result(0, false, false, null, null);
            }
            return projects.execute(request.project(), request.callId(), request.command(), request.workingDirectory(),
                timeout, job.control());
        } finally {
            release(id);
        }
    }

    private synchronized Active acquire(String id) throws IOException {
        if (cancelled.containsKey(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_CANCELLED", "执行请求已经取消");
        }
        if (active.size() >= maximum) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SANDBOX_BUSY", "执行节点已达到任务数量上限");
        }
        if (active.containsKey(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_REQUEST_EXISTS", "此执行请求已经存在");
        }
        Path directory = Files.createDirectory(root.resolve(id));
        var job = new Active(new ToolCallControl(() -> {
        }), directory, System.nanoTime() + Duration.ofSeconds(settings.timeoutSeconds() + 30L).toNanos());
        active.put(id, job);
        return job;
    }

    public synchronized void cancel(String id) {
        if (cancelled.size() < 10000) {
            cancelled.put(id, System.nanoTime() + Duration.ofSeconds(settings.timeoutSeconds() + 60L).toNanos());
        }
        var job = active.get(id);
        if (job != null) {
            job.control().close();
        }
    }

    private void release(String id) {
        var job = active.get(id);
        if (job != null) {
            job.control().close();
            try {
                Path pending = job.directory().resolve("sandbox.pending");
                if (Files.isRegularFile(pending)) {
                    new DockerWorkspaceCommands().removeForWorkspace(Files.readString(pending).trim());
                }
                WorkspaceStore.deleteTree(root, job.directory());
            } catch (IOException | RuntimeException failure) {
                LOG.warn("独立执行目录清理失败，执行编号 {}", id, failure);
            } finally {
                active.remove(id, job);
            }
        }
    }

    private void recoverAbandoned() throws IOException {
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                if (!path.getFileName().toString().matches("[0-9a-f-]{36}")) {
                    continue;
                }
                Path pending = path.resolve("sandbox.pending");
                if (Files.isRegularFile(pending)) {
                    new DockerWorkspaceCommands().removeForWorkspace(Files.readString(pending).trim());
                }
                WorkspaceStore.deleteTree(root, path);
            }
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        active.values().forEach(job -> job.control().close());
        try {
            owner.close();
            ownership.close();
        } catch (IOException failure) {
            LOG.warn("独立执行目录的进程锁释放失败，工作编号 sandbox-server-close", failure);
        }
    }
}
