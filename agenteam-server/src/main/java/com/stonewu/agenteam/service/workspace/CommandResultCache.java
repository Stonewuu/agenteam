package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.CommandResultSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.UserWorkspaceLock;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/** 输出文件与不可由沙盒修改的登记分开存放，读取和清理共用跨进程锁。 */
@Component
public class CommandResultCache {
    private static final Logger LOG = LoggerFactory.getLogger(CommandResultCache.class);
    public static final String PREFIX = "command-results";
    private static final Pattern CALL = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

    @FunctionalInterface
    public interface OutputWriter {
        void write(Path directory) throws IOException;
    }

    public record Area(Path data, Path records, String scope) {
        public Area {
            if (scope == null || !CALL.matcher(scope).matches()) {
                throw WorkspacePaths.invalid();
            }
        }

        public Path files() {
            return data.resolve(scope);
        }
    }

    private record Entry(String scope, String call, long createdAt, long completedAt, long accessedAt,
                         long reservedBytes) {
        boolean running() {
            return completedAt == 0;
        }
    }

    private final CommandResultSettings settings;
    private final WorkspaceSettings workspace;
    private final ObjectMapper json;
    private final Clock clock;

    public CommandResultCache(CommandResultSettings settings, WorkspaceSettings workspace, ObjectMapper json,
                              Clock clock) {
        this.settings = settings;
        this.workspace = workspace;
        this.json = json;
        this.clock = clock;
    }

    public static boolean matches(String path) {
        return path != null && (path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    public boolean capacityExceeded(Area area) throws IOException {
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.acquire(area.records(), area.records().resolve(".lock"), Duration.ZERO, control)) {
            return diskBytes(area.data()) > settings.maximumBytes();
        } catch (ApiException failure) {
            if ("PROJECT_BUSY".equals(failure.code())) {
                // 读取或清理尚未结束时留待下一轮统计，避免因文件正在删除而取消运行中的命令。
                LOG.debug("缓存仍在使用，稍后检查容量，范围编号 {}", area.scope(), failure);
                return false;
            }
            throw failure;
        }
    }

    public static String output(String call, String stream) {
        requireCall(call);
        if (!List.of("stdout", "stderr").contains(stream)) {
            throw WorkspacePaths.invalid();
        }
        return PREFIX + "/" + call + "/" + stream + ".txt";
    }

    public boolean contains(Area area, String call) {
        return Files.isRegularFile(recordPath(area, call), LinkOption.NOFOLLOW_LINKS);
    }

    /** 输出接收和完整读取互斥，重复恢复不会覆盖已经接收的结果。 */
    public void capture(Area area, String call, ToolCallControl control, OutputWriter writer) throws IOException {
        try (var lock = lock(area, control)) {
            var entry = load(area, recordPath(area, call));
            if (!entry.running()) {
                return;
            }
            Path directory = area.files().resolve(PREFIX).resolve(call);
            WorkspacePaths.requireInside(area.data(), directory);
            writer.write(directory);
            for (String name : List.of("stdout.txt", "stderr.txt")) {
                Path path = directory.resolve(name);
                WorkspacePaths.requireInside(area.data(), path);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > workspace.fileBytes()) {
                    throw WorkspacePaths.capacity();
                }
            }
            long now = clock.millis();
            save(area, new Entry(area.scope(), call, entry.createdAt(), now, now, 0));
        }
    }

    /** 调用者已持有容器状态锁；未登记到执行器的准备项没有启动命令，可以回收。 */
    public void reconcile(Area area, BiPredicate<String, String> finished, ToolCallControl control) throws IOException {
        try (var lock = lock(area, control)) {
            for (var entry : entries(area)) {
                if (entry.running() && finished.test(entry.scope(), entry.call())) {
                    long now = clock.millis();
                    save(new Area(area.data(), area.records(), entry.scope()),
                        new Entry(entry.scope(), entry.call(), entry.createdAt(), now, now, 0));
                }
            }
        }
    }

    /** 临时执行的传输结果在发布工作目录前转入缓存，不复制到项目版本中。 */
    public SandboxExecutor.Result receive(Area area, String call, SandboxExecutor.Result result,
                                          Path source, ToolCallControl control) throws IOException {
        try (var lock = lock(area, control)) {
            if (!contains(area, call)) {
                throw expired();
            }
            for (String stream : List.of("stdout", "stderr")) {
                String path = stream.equals("stdout") ? result.stdoutPath() : result.stderrPath();
                if (path == null) {
                    continue;
                }
                Path original = source.resolve(WorkspacePaths.projectLogical(path));
                WorkspacePaths.requireInside(source, original);
                Path destination = area.files().resolve(output(call, stream));
                WorkspacePaths.requireInside(area.data(), destination);
                if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                    Files.write(destination, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                } else if (Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.size(original) > workspace.fileBytes()) {
                        throw WorkspacePaths.capacity();
                    }
                    Files.copy(original, destination, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                } else {
                    throw WorkspacePaths.invalid();
                }
            }
            var entry = load(area, recordPath(area, call));
            long now = clock.millis();
            save(area, new Entry(area.scope(), call, entry.createdAt(), now, now, 0));
            for (String path : new String[]{result.stdoutPath(), result.stderrPath()}) {
                if (path != null) {
                    Path original = source.resolve(WorkspacePaths.projectLogical(path));
                    WorkspacePaths.requireInside(source, original);
                    Files.deleteIfExists(original);
                }
            }
            Path old = source.resolve("work/command-results/" + call);
            WorkspacePaths.requireInside(source, old);
            if (Files.isDirectory(old, LinkOption.NOFOLLOW_LINKS)) {
                try (var remaining = Files.list(old)) {
                    if (remaining.findAny().isEmpty()) {
                        Files.delete(old);
                    }
                }
            }
            Path parent = old.getParent();
            if (Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                try (var remaining = Files.list(parent)) {
                    if (remaining.findAny().isEmpty()) {
                        Files.delete(parent);
                    }
                }
            }
            return new SandboxExecutor.Result(result.exitCode(), result.timedOut(), result.capacityExceeded(),
                output(call, "stdout"), output(call, "stderr"));
        }
    }

    public Path prepare(Area area, String call, Set<String> protectedScopes, ToolCallControl control) throws IOException {
        requireCall(call);
        try (var lock = lock(area, control)) {
            if (Files.exists(recordPath(area, call), LinkOption.NOFOLLOW_LINKS)) {
                throw new ApiException(HttpStatus.CONFLICT, "COMMAND_RESULT_EXISTS", "此命令结果已经登记，请先核实原执行结果。");
            }
            long reserved = 2L * workspace.fileBytes();
            cleanLocked(area, protectedScopes, reserved, 1);
            Path directory = area.files().resolve(PREFIX).resolve(call);
            WorkspacePaths.requireInside(area.data(), directory);
            Files.createDirectories(directory);
            for (Path path : List.of(area.data(), area.files(), directory.getParent(), directory)) {
                WorkspacePaths.requireInside(area.data(), path);
                if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(path.equals(directory) ? "rwxrwxrwx" : "rwxr-xr-x"));
                }
            }
            long now = clock.millis();
            save(area, new Entry(area.scope(), call, now, 0, now, reserved));
            return directory;
        }
    }

    /** 只有执行器确认进程退出后才能释放预留容量。 */
    public void complete(Area area, String call, ToolCallControl control) throws IOException {
        if (!contains(area, call)) {
            return;
        }
        capture(area, call, control, directory -> {
            for (String name : List.of("stdout.txt", "stderr.txt")) {
                Path path = directory.resolve(name);
                WorkspacePaths.requireInside(area.data(), path);
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.write(path, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                }
            }
        });
    }

    public <T> T read(Area area, String path, ToolCallControl control, Function<WorkspaceFilesystem, T> action)
        throws IOException {
        String logical = WorkspacePaths.projectLogical(path);
        if (!matches(logical)) {
            throw WorkspacePaths.invalid();
        }
        try (var lock = lock(area, control)) {
            var selected = entries(area).stream().filter(entry -> entry.scope().equals(area.scope())).toList();
            if (!logical.equals(PREFIX)) {
                String[] parts = logical.split("/");
                if (parts.length < 2 || parts.length > 3 || !CALL.matcher(parts[1]).matches()
                    || parts.length == 3 && !List.of("stdout.txt", "stderr.txt").contains(parts[2])) {
                    throw WorkspacePaths.invalid();
                }
                selected = selected.stream().filter(entry -> entry.call().equals(parts[1])).toList();
                if (selected.isEmpty()) {
                    throw expired();
                }
            }
            for (var entry : selected) {
                if (entry.running()) {
                    throw new ApiException(HttpStatus.CONFLICT, "COMMAND_RESULT_RUNNING", "命令仍在执行，请等待执行完成后读取输出。");
                }
                save(area, new Entry(entry.scope(), entry.call(), entry.createdAt(), entry.completedAt(), clock.millis(), 0));
            }
            Files.createDirectories(area.files().resolve(PREFIX));
            WorkspacePaths.requireInside(area.data(), area.files());
            var files = new WorkspaceFilesystem(area.files(), workspace, control::requireActive, false, true);
            return action.apply(files);
        }
    }

    public int clean(Area area, Set<String> protectedScopes, ToolCallControl control) throws IOException {
        if (!Files.isDirectory(area.records(), LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (var lock = lock(area, control)) {
            return cleanLocked(area, protectedScopes, 0, 0);
        }
    }

    private int cleanLocked(Area area, Set<String> protectedScopes, long reserve, int additional) throws IOException {
        var entries = new ArrayList<>(entries(area));
        entries.sort(Comparator.comparingLong(entry -> Math.max(entry.completedAt(), entry.accessedAt())));
        long used = diskBytes(area.data());
        for (var entry : entries) {
            if (entry.running()) {
                used += Math.max(0, entry.reservedBytes() - size(area, entry));
            }
        }
        int count = entries.size(), removed = 0;
        long cutoff = clock.millis() - Duration.ofDays(settings.retentionDays()).toMillis();
        for (var entry : entries) {
            if (entry.running() || protectedScopes.contains(entry.scope())) {
                continue;
            }
            boolean expired = Math.max(entry.completedAt(), entry.accessedAt()) < cutoff;
            if (!expired && used + reserve <= settings.maximumBytes() && count + additional <= settings.maximumEntries()) {
                continue;
            }
            long size = size(area, entry);
            Path directory = area.data().resolve(entry.scope()).resolve(PREFIX).resolve(entry.call());
            WorkspaceStore.deleteTree(area.data(), directory);
            Files.delete(recordPath(new Area(area.data(), area.records(), entry.scope()), entry.call()));
            used -= size;
            count--;
            removed++;
        }
        if (reserve > 0 && (used + reserve > settings.maximumBytes() || count + additional > settings.maximumEntries())) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "COMMAND_RESULT_CAPACITY",
                "命令结果缓存空间不足，现有任务仍在使用的输出已保留，请稍后重试或联系管理员调整容量。");
        }
        return removed;
    }

    private long size(Area area, Entry entry) throws IOException {
        Path directory = area.data().resolve(entry.scope()).resolve(PREFIX).resolve(entry.call());
        WorkspacePaths.requireInside(area.data(), directory);
        long size = 0;
        for (String name : List.of("stdout.txt", "stderr.txt")) {
            Path file = directory.resolve(name);
            WorkspacePaths.requireInside(area.data(), file);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                size += Files.size(file);
            }
        }
        return size;
    }

    private long diskBytes(Path directory) throws IOException {
        long bytes = 0;
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.filter(value -> Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    WorkspacePaths.requireInside(directory, path);
                    bytes += Files.size(path);
                }
            }
        }
        return bytes;
    }

    private List<Entry> entries(Area area) throws IOException {
        var result = new ArrayList<Entry>();
        try (var paths = Files.walk(area.records(), 2)) {
            for (Path path : paths.filter(value -> value.getFileName().toString().endsWith(".json")).toList()) {
                result.add(load(area, path));
            }
        }
        return result;
    }

    private Entry load(Area area, Path path) throws IOException {
        WorkspacePaths.requireInside(area.records(), path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096) {
            throw WorkspacePaths.invalid();
        }
        var entry = json.readValue(Files.readAllBytes(path), Entry.class);
        requireCall(entry.call());
        requireCall(entry.scope());
        if (!path.equals(recordPath(new Area(area.data(), area.records(), entry.scope()), entry.call()))) {
            throw WorkspacePaths.invalid();
        }
        return entry;
    }

    private void save(Area area, Entry entry) throws IOException {
        Path path = recordPath(area, entry.call());
        Files.createDirectories(path.getParent());
        WorkspacePaths.requireInside(area.records(), path);
        Path temporary = path.resolveSibling(entry.call() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, json.writeValueAsBytes(entry), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path recordPath(Area area, String call) {
        requireCall(call);
        return area.records().resolve(area.scope()).resolve(call + ".json");
    }

    private UserWorkspaceLock lock(Area area, ToolCallControl control) throws IOException {
        Files.createDirectories(area.records());
        Files.createDirectories(area.data());
        return UserWorkspaceLock.acquire(area.records(), area.records().resolve(".lock"), LOCK_TIMEOUT, control);
    }

    private static void requireCall(String call) {
        if (call == null || !CALL.matcher(call).matches()) {
            throw WorkspacePaths.invalid();
        }
    }

    public static ApiException expired() {
        return new ApiException(HttpStatus.GONE, "COMMAND_RESULT_EXPIRED", "命令完整输出已清理或不可用，请根据已有结果继续处理；需要再次执行时请先确认不会重复修改数据。");
    }
}
