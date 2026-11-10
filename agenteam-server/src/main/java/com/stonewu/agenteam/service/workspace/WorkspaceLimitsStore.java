package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.model.workspace.entity.WorkspaceLimitValues;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.UserWorkspaceLock;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Objects;

/** 后端与独立执行服务读取共享配置；该目录不会挂载到用户容器。 */
@Component
public class WorkspaceLimitsStore {
    public record Saved(long revision, WorkspaceLimitValues limits) {
        public Saved {
            if (revision < 2 || limits == null) {
                throw new IllegalArgumentException("工作空间设置记录不完整");
            }
        }
    }

    private final ProjectWorkspaceLayout layout;
    private final Path directory;
    private final Path file;
    private BasicFileAttributes previous;
    private Saved cached;

    public WorkspaceLimitsStore(ProjectWorkspaceLayout layout) {
        this.layout = layout;
        directory = layout.root().resolve(".platform-settings");
        file = directory.resolve("workspace-limits.json");
    }

    public synchronized Saved current() {
        try {
            WorkspacePaths.requireInside(layout.root(), file);
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > 4096) {
                throw new IOException("工作空间设置文件格式不正确");
            }
            if (cached == null || previous == null || attributes.size() != previous.size()
                || !attributes.lastModifiedTime().equals(previous.lastModifiedTime())
                || !Objects.equals(attributes.fileKey(), previous.fileKey())) {
                cached = layout.read(file, Saved.class);
                previous = attributes;
            }
            return cached;
        } catch (NoSuchFileException missing) {
            cached = null;
            previous = null;
            return null;
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    public synchronized Saved save(long revision, WorkspaceLimitValues limits) {
        try {
            Files.createDirectories(layout.root());
            WorkspacePaths.requireInside(layout.root(), directory);
            Files.createDirectories(directory);
            try (var control = new ToolCallControl(() -> {
            }); var lock = UserWorkspaceLock.acquire(layout.root(), directory.resolve("settings.lock"),
                Duration.ofSeconds(10), control)) {
                var saved = current();
                long currentRevision = saved == null ? 1 : saved.revision();
                if (currentRevision != revision) {
                    throw ApiException.versionConflict(currentRevision);
                }
                var next = new Saved(Math.addExact(currentRevision, 1), limits);
                layout.write(file, next);
                previous = null;
                cached = null;
                return next;
            }
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    private static ApiException unavailable(IOException failure) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKSPACE_SETTINGS_UNAVAILABLE",
            "暂时无法读取或保存工作空间设置，请稍后重试。", failure);
    }
}
