package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.UserSandboxRegistry;
import com.stonewu.agenteam.service.project.UserWorkspaceLock;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 清理与执行登记按同一顺序加锁；任务仍在思考或读取时保留其项目输出。 */
@Service
public class CommandResultRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(CommandResultRetentionService.class);
    private static final Duration WAIT = Duration.ofMillis(200);
    private static final int LEGACY_BATCH = 50;
    private final Map<String, String> legacyAfter = new ConcurrentHashMap<>();
    private final ProjectWorkspaceLayout layout;
    private final UserSandboxRegistry registry;
    private final WorkspaceStore previews;
    private final ConversationMapper conversations;
    private final CommandResultCache cache;

    public CommandResultRetentionService(ProjectWorkspaceLayout layout, UserSandboxRegistry registry,
                                          WorkspaceStore previews, ConversationMapper conversations, CommandResultCache cache) {
        this.layout = layout;
        this.registry = registry;
        this.previews = previews;
        this.conversations = conversations;
        this.cache = cache;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void poll() {
        try {
            for (String workspace : registry.workspaces()) {
                try {
                    cleanProject(workspace);
                } catch (IOException | RuntimeException failure) {
                    if (failure instanceof ApiException known && "PROJECT_BUSY".equals(known.code())) {
                        LOG.debug("工作空间仍在使用，稍后清理命令结果，工作空间编号 {}", workspace, failure);
                    } else {
                        LOG.warn("命令结果缓存暂未清理，工作空间编号 {}", workspace, failure);
                    }
                }
            }
            if (Files.isDirectory(previews.root(), LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(previews.root(), 4)) {
                    for (Path directory : paths.filter(path -> previews.root().relativize(path).getNameCount() == 4
                        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                        try {
                            cleanPreview(directory);
                        } catch (IOException | RuntimeException failure) {
                            if (failure instanceof ApiException known && "PROJECT_BUSY".equals(known.code())) {
                                LOG.debug("预览仍在使用，稍后清理命令结果，目录编号 {}", directory.getFileName(), failure);
                            } else {
                                LOG.warn("预览命令结果缓存暂未清理，目录编号 {}", directory.getFileName(), failure);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warn("扫描命令结果缓存失败，任务编号 command-result-retention", failure);
        }
    }

    public void cleanProject(String workspace) throws IOException {
        if (!Files.isDirectory(layout.control(workspace).resolve("command-results"), LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(layout.control(workspace).resolve("commands"), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var control = new ToolCallControl(() -> {
        }); var files = UserWorkspaceLock.acquire(layout, workspace, WAIT, control);
             var state = UserWorkspaceLock.runtime(layout, workspace, WAIT, control)) {
            var area = new CommandResultCache.Area(layout.workspace(workspace).resolve("cache"),
                layout.control(workspace).resolve("command-results"), "cleanup");
            Set<String> protectedScopes = registry.usages(workspace).stream().map(usage -> usage.project().projectId())
                .collect(Collectors.toSet());
            registry.active(workspace).forEach(command -> protectedScopes.add(command.projectId()));
            cache.reconcile(area, (scope, call) -> {
                try {
                    var record = registry.command(workspace, call);
                    return record == null || "interrupted".equals(record.status());
                } catch (IOException failure) {
                    throw WorkspacePaths.io(failure);
                }
            }, control);
            var commands = registry.commands(workspace, legacyAfter.get(workspace), LEGACY_BATCH);
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            boolean exhausted = true;
            for (var record : commands) {
                if (System.nanoTime() > deadline) {
                    exhausted = false;
                    break;
                }
                legacyAfter.put(workspace, record.callId());
                var result = record.result();
                if (result == null || protectedScopes.contains(record.projectId()) || result.stdoutPath() == null
                    || !result.stdoutPath().equals("work/command-results/" + record.callId() + "/stdout.txt")
                    || !("work/command-results/" + record.callId() + "/stderr.txt").equals(result.stderrPath())) {
                    continue;
                }
                var projectArea = layout.commandResults(record.project());
                Path source = layout.project(record.project());
                Path original = source.resolve(result.stdoutPath());
                WorkspacePaths.requireInside(layout.root(), original);
                if (Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
                    if (!cache.contains(projectArea, record.callId())) {
                        cache.prepare(projectArea, record.callId(), protectedScopes, control);
                    }
                    cache.receive(projectArea, record.callId(), result, source, control);
                }
            }
            if (exhausted && commands.size() < LEGACY_BATCH) {
                legacyAfter.remove(workspace);
            }
            cache.clean(area, protectedScopes, control);
        }
    }

    public void cleanPreview(Path directory) throws IOException {
        Path records = directory.resolve("command-results");
        if (!Files.isDirectory(records, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        WorkspacePaths.requireInside(previews.root(), directory);
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.acquire(previews.root(), directory.resolve(".lock"), WAIT, control)) {
            if (Files.exists(directory.resolve("sandbox.pending"), LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            var path = previews.root().relativize(directory);
            var record = conversations.find(decode(path.getName(0)), decode(path.getName(1)), decode(path.getName(2)), false);
            if (record.isPresent() && record.get().activeRunId() != null) {
                return;
            }
            var area = new CommandResultCache.Area(directory.resolve("cache"), records, "preview");
            cache.reconcile(area, (scope, call) -> true, control);
            cache.clean(area, Set.of(), control);
        }
    }

    private String decode(Path component) {
        return new String(Base64.getUrlDecoder().decode(component.toString()), StandardCharsets.UTF_8);
    }
}
