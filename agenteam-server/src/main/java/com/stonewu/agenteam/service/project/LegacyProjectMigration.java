package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxContainerCleanup;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 旧目录只复制和校验，保留原副本；不同子会话的同名文件不互相覆盖。
 */
@Component
public class LegacyProjectMigration {
    public record Origins(Set<String> results, Set<String> inputs) {
    }

    private final WorkspaceStore legacy;
    private final ToolCallMapper calls;
    private final ObjectMapper json;
    private final SandboxContainerCleanup containers;

    public LegacyProjectMigration(WorkspaceStore legacy, ToolCallMapper calls, ObjectMapper json,
                                  SandboxContainerCleanup containers) {
        this.legacy = legacy;
        this.calls = calls;
        this.json = json;
        this.containers = containers;
    }

    public Origins copy(ProjectLocation location, String conversation, Path target, ToolCallControl control,
                        Duration timeout) throws IOException {
        var results = new LinkedHashSet<String>();
        var inputs = new LinkedHashSet<String>();
        if (conversation == null) {
            return new Origins(results, inputs);
        }
        Path main = legacy.directory(location.enterpriseId(), location.userId(), conversation, conversation);
        if (!Files.isDirectory(main.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            if (calls.hasConversationWorkspace(location.enterpriseId(), location.userId(), conversation)) {
                throw ProjectWorkspaceLayout.unavailable();
            }
            return new Origins(results, inputs);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try (var sessions = Files.list(main.getParent())) {
            for (Path session : sessions.toList()) {
                if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                WorkspacePaths.requireInside(legacy.root(), session);
                try (var channel = FileChannel.open(session.resolve(".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     var lock = acquire(channel, deadline, control)) {
                    Path pending = session.resolve("sandbox.pending");
                    if (Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS)) {
                        WorkspacePaths.requireInside(legacy.root(), pending);
                        if (Files.size(pending) > 128) {
                            throw WorkspacePaths.invalid();
                        }
                        containers.removeForWorkspace(Files.readString(pending).trim(),
                            Duration.ofNanos(Math.max(1, deadline - System.nanoTime())), control);
                        Files.delete(pending);
                    }
                    Path pointer = session.resolve("current.json");
                    if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)
                            || session.equals(main) && calls.hasConversationWorkspace(location.enterpriseId(),
                            location.userId(), conversation)
                            && !Files.isRegularFile(session.resolve("cleared.json"), LinkOption.NOFOLLOW_LINKS)) {
                            throw ProjectWorkspaceLayout.unavailable();
                        }
                        continue;
                    }
                    if (Files.size(pointer) > 2 * 1024 * 1024) {
                        throw WorkspacePaths.capacity();
                    }
                    var saved = json.readValue(Files.readAllBytes(pointer), WorkspaceStore.Manifest.class);
                    if (!location.enterpriseId().equals(saved.enterpriseId()) || !location.userId()
                        .equals(saved.userId())
                        || !conversation.equals(saved.conversationId()) || saved.version() == null || !saved.version()
                        .matches("[0-9a-f-]{36}")
                        || saved.sessionId() == null || !EncryptedAgentStateStore.component(saved.sessionId())
                        .equals(session.getFileName().toString())
                        || saved.inputFileIds() == null || saved.sourceResultIds() == null) {
                        throw WorkspacePaths.invalid();
                    }
                    Path source = session.resolve("versions").resolve(saved.version());
                    WorkspacePaths.requireInside(legacy.root(), source);
                    Path destination = session.equals(main) ? target : target.resolve("work/legacy-sessions")
                        .resolve(session.getFileName());
                    WorkspacePaths.requireInside(target, destination);
                    Files.createDirectories(destination);
                    copyTree(source, destination, control);
                    results.addAll(saved.sourceResultIds());
                    inputs.addAll(saved.inputFileIds());
                }
            }
        }
        return new Origins(Set.copyOf(results), Set.copyOf(inputs));
    }

    private FileLock acquire(FileChannel channel, long deadline, ToolCallControl control) throws IOException {
        while (true) {
            control.requireActive();
            try {
                var lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException busy) {
                // 旧版本的操作完成后再复制已保存内容。
            }
            if (System.nanoTime() >= deadline) {
                throw ApiException.invalidField("projectId", "旧会话文件仍在使用，请等待任务停止后重试。");
            }
            control.pause(Duration.ofMillis(50));
        }
    }

    private void copyTree(Path source, Path target, ToolCallControl control) throws IOException {
        try (var paths = Files.walk(source)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                control.requireActive();
                Path path = iterator.next();
                WorkspacePaths.requireInside(source, path);
                Path destination = target.resolve(source.relativize(path));
                WorkspacePaths.requireInside(target, destination);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination.getParent());
                    WorkspacePaths.requireInside(target, destination);
                    if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    if (Files.mismatch(path, destination) != -1) {
                        throw ApiException.invalidField("projectId",
                            "项目目录已有不同内容，未覆盖文件，请核对目录后重试。");
                    }
                } else {
                    throw WorkspacePaths.invalid();
                }
            }
        }
    }
}
