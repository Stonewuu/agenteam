package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.file.Utf8Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 不清理正在使用的目录；正常工作文件按空闲时间保留，删除对话仍保留可恢复期限。
 */
@Service
public class WorkspaceRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceRetentionService.class);
    private final WorkspaceStore store;
    private final ConversationMapper conversations;
    private final SandboxContainerCleanup containers;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;
    private final Duration retention;
    private String after;

    public WorkspaceRetentionService(WorkspaceStore store, ConversationMapper conversations,
                                     SandboxContainerCleanup containers, ObjectMapper json, Clock clock,
                                     @Value("${execution.workspace.retention-enabled:true}") boolean enabled,
                                     @Value("${execution.workspace.retention-days:30}") int days) {
        if (days < 1 || days > 3650) {
            throw new IllegalArgumentException("工作文件保留时间超出允许范围");
        }
        this.store = store;
        this.conversations = conversations;
        this.containers = containers;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
        this.retention = Duration.ofDays(days);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            clean();
        } catch (RuntimeException failure) {
            LOG.warn("工作文件清理暂未完成，稍后重试", failure);
        }
    }

    public synchronized int clean() {
        Path root = store.root();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        try (var paths = Files.walk(root, 4)) {
            var candidates = paths.filter(
                    path -> root.relativize(path).getNameCount() == 4 && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .map(path -> root.relativize(path).toString())
                .filter(path -> after == null || path.compareTo(after) > 0).sorted().limit(100).toList();
            for (String key : candidates) {
                after = key;
                Path scope = root.resolve(key);
                try {
                    if (cleanScope(root, scope)) {
                        removed++;
                    }
                } catch (RuntimeException | IOException failure) {
                    LOG.warn("工作文件目录清理失败，目录编号 {}", Utf8Text.revision("workspace", key), failure);
                }
            }
            if (candidates.size() < 100) {
                after = null;
            }
            return removed;
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    private boolean cleanScope(Path root, Path directory) throws IOException {
        WorkspacePaths.requireInside(root, directory);
        var identity = root.relativize(directory);
        String enterprise = decode(identity.getName(0).toString()), user = decode(
            identity.getName(1).toString()), conversation = decode(identity.getName(2).toString());
        decode(identity.getName(3).toString());
        Path lockPath = directory.resolve(".lock");
        WorkspacePaths.requireInside(root, lockPath);
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        boolean discard;
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException held) {
                return false;
            }
            if (lock == null) {
                return false;
            }
            try (lock) {
                var lastUsed = Files.getLastModifiedTime(directory);
                if (Files.isRegularFile(directory.resolve("sandbox.pending"), LinkOption.NOFOLLOW_LINKS)) {
                    containers.removeForWorkspace(Utf8Text.revision("workspace", directory.toString()));
                    Files.delete(directory.resolve("sandbox.pending"));
                }
                var record = conversations.find(enterprise, user, conversation, false);
                if (record.isPresent() && record.get().mode().equals("normal") && record.get().projectId() != null) {
                    // 已关联项目的旧目录保留为迁移来源，不随会话到期或删除清理。
                    return false;
                }
                if (record.isPresent() && record.get().activeRunId() != null) {
                    return false;
                }
                removeAbandonedFiles(directory);
                Files.setLastModifiedTime(directory, lastUsed);
                boolean expired;
                if (record.isEmpty()) {
                    expired = Files.getLastModifiedTime(directory).toInstant()
                        .isBefore(clock.instant().minus(Duration.ofDays(1)));
                } else if (record.get().status().equals("deleted")) {
                    expired = record.get().deletedAt() != null && record.get().deletedAt()
                        .isBefore(clock.instant().minus(Duration.ofDays(30)));
                } else if (record.get().mode().equals("preview")) {
                    expired = record.get().createdAt().isBefore(clock.instant().minus(Duration.ofDays(7)));
                } else {
                    expired = Files.getLastModifiedTime(directory).toInstant()
                        .isBefore(clock.instant().minus(retention));
                }
                if (!expired) {
                    return false;
                }
                if (!expireFiles(directory, enterprise, user, conversation)) {
                    return false;
                }
                discard = record.isEmpty() || record.get().status().equals("deleted") || record.get().mode()
                    .equals("preview");
            }
        }
        if (discard) {
            WorkspaceStore.deleteTree(root, directory);
        }
        return true;
    }

    private void removeAbandonedFiles(Path directory) throws IOException {
        Path pointer = directory.resolve("current.json");
        WorkspacePaths.requireInside(directory, pointer);
        if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS) && Files.size(pointer) > 2 * 1024 * 1024) {
            throw WorkspacePaths.capacity();
        }
        String currentVersion = Files.exists(pointer, LinkOption.NOFOLLOW_LINKS) ? json.readTree(
            Files.readAllBytes(pointer)).path("version").asText() : null;
        Path versions = directory.resolve("versions");
        WorkspacePaths.requireInside(directory, versions);
        if (Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(versions)) {
                for (var version : entries.toList()) {
                    if (!version.getFileName().toString().equals(currentVersion)
                        && Files.getLastModifiedTime(version, LinkOption.NOFOLLOW_LINKS).toInstant()
                        .isBefore(clock.instant().minus(Duration.ofHours(1)))) {
                        WorkspaceStore.deleteTree(directory, version);
                    }
                }
            }
        }
        try (var children = Files.list(directory)) {
            for (var child : children.toList()) {
                String name = child.getFileName().toString();
                if ((name.startsWith("expired-") || name.startsWith("command-")) && Files.getLastModifiedTime(child)
                    .toInstant().isBefore(clock.instant().minus(Duration.ofHours(1)))) {
                    WorkspaceStore.deleteTree(directory, child);
                }
            }
        }
    }

    private boolean expireFiles(Path directory, String enterprise, String user,
                                String conversation) throws IOException {
        Path retired = Files.createDirectory(directory.resolve("expired-" + UUID.randomUUID()));
        boolean committed = false;
        try {
            for (String name : List.of("versions", "empty")) {
                if (Files.exists(directory.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(directory.resolve(name), retired.resolve(name), StandardCopyOption.ATOMIC_MOVE);
                }
            }
            // 若新任务在清理准备期间启动，原样恢复目录，不让它读到半份工作文件。
            var current = conversations.find(enterprise, user, conversation, false);
            if (current.isPresent() && current.get().activeRunId() != null) {
                return false;
            }
            Files.write(directory.resolve("cleared.json"), json.writeValueAsBytes(
                    Map.of("clearedAt", clock.instant().toString(), "reason", "工作文件超过保留时间")),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            Files.deleteIfExists(directory.resolve("current.json"));
            committed = true;
            return true;
        } finally {
            if (!committed) {
                for (String name : List.of("versions", "empty")) {
                    if (Files.exists(retired.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(retired.resolve(name), directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
                    }
                }
            }
            WorkspaceStore.deleteTree(directory, retired);
        }
    }

    private static String decode(String component) {
        if (!component.startsWith("v-")) {
            throw WorkspacePaths.invalid();
        }
        String result = new String(Base64.getUrlDecoder().decode(component.substring(2)), StandardCharsets.UTF_8);
        if (result.isBlank() || !EncryptedAgentStateStore.component(result).equals(component)) {
            throw WorkspacePaths.invalid();
        }
        return result;
    }
}
