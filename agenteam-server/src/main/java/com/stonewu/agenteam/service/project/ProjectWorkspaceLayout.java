package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/**
 * 控制文件与可挂载的用户文件分开保存，独立执行节点读取同一份项目登记。
 */
@Component
public class ProjectWorkspaceLayout {
    private record FileRevision(String version) {
    }

    private final UserWorkspaceSettings settings;
    private final ObjectMapper json;

    public ProjectWorkspaceLayout(UserWorkspaceSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public Path root() {
        return Path.of(settings.root());
    }

    public Path workspace(String workspaceId) {
        if (workspaceId == null || !workspaceId.matches("[0-9a-f]{64}")) {
            throw WorkspacePaths.invalid();
        }
        return root().resolve(workspaceId);
    }

    public Path files(String workspaceId) {
        return workspace(workspaceId).resolve("files");
    }

    public Path control(String workspaceId) {
        return workspace(workspaceId).resolve("control");
    }

    public Path project(ProjectLocation location) {
        validate(location);
        return files(location.workspaceId()).resolve(location.directory());
    }

    public Path state(ProjectLocation location) {
        validate(location);
        return control(location.workspaceId()).resolve("project-" + location.projectId() + ".state.json");
    }

    public Path registration(ProjectLocation location) {
        validate(location);
        return control(location.workspaceId()).resolve("project-" + location.projectId() + ".json");
    }

    public String hostPath(Path path) {
        return settings.hostPath(path);
    }

    /**
     * 命令可以在用户授权后修改其他项目，因此文件版本属于整个用户空间。
     */
    public void recordFileChange(String workspaceId) throws IOException {
        write(control(workspaceId).resolve("files-revision.json"), new FileRevision(UUID.randomUUID().toString()));
    }

    public String fileRevision(String workspaceId) throws IOException {
        Path path = control(workspaceId).resolve("files-revision.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        var revision = read(path, FileRevision.class).version();
        if (revision == null || !revision.matches("[0-9a-f-]{36}")) {
            throw unavailable();
        }
        return revision;
    }

    public void prepareWorkspace(String id, boolean initialized) throws IOException {
        Files.createDirectories(root());
        Path directory = workspace(id);
        WorkspacePaths.requireInside(root(), directory);
        if (initialized && (!Files.isDirectory(files(id), LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(control(id),
            LinkOption.NOFOLLOW_LINKS))) {
            throw unavailable();
        }
        boolean created = !Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        Files.createDirectories(directory);
        if (created && directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(directory,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
        Files.createDirectories(control(id));
        Files.createDirectories(files(id));
        WorkspacePaths.requireInside(root(), control(id));
        WorkspacePaths.requireInside(root(), files(id));
    }

    public void register(ProjectLocation location) throws IOException {
        Path file = registration(location);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!location.equals(read(file, ProjectLocation.class))) {
                throw unavailable();
            }
            return;
        }
        write(file, location);
    }

    public void requireRegistered(ProjectLocation location) throws IOException {
        try {
            if (!location.equals(read(registration(location), ProjectLocation.class)) || !Files.isDirectory(
                project(location), LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable();
            }
            WorkspacePaths.requireInside(root(), project(location));
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_STORAGE_UNAVAILABLE",
                "暂时无法读取已登记的项目目录，请检查持久存储及执行节点的挂载配置。", failure);
        }
    }

    public <T> T read(Path path, Class<T> type) throws IOException {
        WorkspacePaths.requireInside(root(), path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 2 * 1024 * 1024) {
            throw unavailable();
        }
        return json.readValue(Files.readAllBytes(path), type);
    }

    public void write(Path path, Object value) throws IOException {
        WorkspacePaths.requireInside(root(), path);
        Path pending = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = json.writeValueAsBytes(value);
            if (bytes.length > 2 * 1024 * 1024) {
                throw WorkspacePaths.capacity();
            }
            Files.write(pending, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private void validate(ProjectLocation location) {
        if (location == null || location.projectId() == null || !location.projectId().matches("[A-Za-z0-9_-]{1,100}")
            || location.enterpriseId() == null || location.userId() == null || location.directory() == null
            || !ProjectPaths.workspaceId(location.enterpriseId(), location.userId()).equals(location.workspaceId())
            || !ProjectPaths.directory(location.directory(), location.projectId()).equals(location.directory())) {
            throw WorkspacePaths.invalid();
        }
        workspace(location.workspaceId());
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_STORAGE_UNAVAILABLE",
            "暂时无法读取已登记的项目目录，请检查持久存储及执行节点的挂载配置。");
    }
}
