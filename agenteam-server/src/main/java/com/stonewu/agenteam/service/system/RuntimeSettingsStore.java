package com.stonewu.agenteam.service.system;

import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.UserWorkspaceLock;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** 后端与独立执行服务读取同一份设置，用户环境不能访问此目录。 */
@Component
public class RuntimeSettingsStore {
    public record Saved(long revision, Map<String, Long> values) {
        public Saved {
            if (revision < 2 || values == null || values.entrySet().stream().anyMatch(entry ->
                RuntimeSetting.find(entry.getKey()).filter(setting -> setting.accepts(entry.getValue())).isEmpty())) {
                throw new IllegalArgumentException("Invalid saved runtime settings");
            }
            values = Map.copyOf(values);
        }
    }

    private final ProjectWorkspaceLayout layout;
    private final Path directory;
    private final Path file;
    private BasicFileAttributes previous;
    private Saved cached;

    public RuntimeSettingsStore(ProjectWorkspaceLayout layout) {
        this.layout = layout;
        directory = layout.root().resolve(".platform-settings");
        file = directory.resolve("runtime-settings.json");
    }

    public synchronized Saved current() {
        try {
            WorkspacePaths.requireInside(layout.root(), file);
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > 16384) {
                throw new IOException("Invalid runtime settings file");
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

    public long value(RuntimeSetting setting, long fallback) {
        var saved = current();
        return saved == null ? fallback : saved.values().getOrDefault(setting.key(), fallback);
    }

    public synchronized Saved save(long revision, Map<String, Long> values) {
        try {
            Files.createDirectories(layout.root());
            WorkspacePaths.requireInside(layout.root(), directory);
            Files.createDirectories(directory);
            try (var control = new ToolCallControl(() -> {
            }); var lock = UserWorkspaceLock.acquire(layout.root(), directory.resolve("runtime-settings.lock"),
                Duration.ofSeconds(10), control)) {
                var saved = current();
                long currentRevision = saved == null ? 1 : saved.revision();
                if (currentRevision != revision) {
                    throw ApiException.versionConflict(currentRevision);
                }
                var next = new Saved(Math.addExact(currentRevision, 1), values);
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
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_SETTINGS_UNAVAILABLE",
            Messages.text("error.system.runtimeSettings.unavailable"), failure);
    }
}
