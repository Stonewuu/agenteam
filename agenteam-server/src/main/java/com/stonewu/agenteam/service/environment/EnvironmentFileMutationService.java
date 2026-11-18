package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.WorkspaceFileChanges;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class EnvironmentFileMutationService {
    private final ProjectMetadataService metadata;
    private final ProjectWorkspaceLayout layout;
    private final WorkspaceSettings settings;

    public EnvironmentFileMutationService(ProjectMetadataService metadata, ProjectWorkspaceLayout layout, WorkspaceSettings settings) {
        this.metadata = metadata;
        this.layout = layout;
        this.settings = settings;
    }

    @Transactional
    public void createDirectory(AuthContext actor, String path, String name) {
        String logical = child(path, name);
        modify(actor, logical, (workspace, target, changes) -> {
            requireParent(target);
            var usage = EnvironmentFileUsage.read(layout.files(workspace));
            if (usage.fileCount() + usage.directoryCount() >= settings.fileCountLimit() * 2L) {
                throw WorkspacePaths.capacity();
            }
            Files.createDirectory(target);
            changes.created(target);
            return 1;
        });
    }

    /** 一批目录共用一次容量检查。重复提交时只补建缺少的目录，已有文件仍由上传接口检查。 */
    @Transactional
    public int prepareUploadDirectories(AuthContext actor, String path, List<String> directories) {
        String base = WorkspacePaths.projectLogical(path);
        var requested = new LinkedHashSet<Path>();
        for (String relative : directories) {
            if (relative == null || relative.isBlank() || !relative.equals(WorkspacePaths.projectLogical(relative))) {
                throw WorkspacePaths.invalid();
            }
            WorkspacePaths.projectLogical(base.isEmpty() ? relative : base + "/" + relative);
            Path prefix = Path.of("");
            for (Path part : Path.of(relative)) {
                prefix = prefix.resolve(part);
                requested.add(prefix);
            }
        }
        return modify(actor, base, (workspace, target, changes) -> {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw EnvironmentFileService.missingDirectory();
            }
            var missing = new LinkedHashSet<Path>();
            for (Path relative : requested) {
                Path directory = target.resolve(relative);
                WorkspacePaths.requireInside(layout.root(), directory);
                if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    missing.add(directory);
                } else if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw ApiException.invalidField("directories", Messages.text("error.environment.uploadFolderConflict",
                        relative.toString().replace('\\', '/')));
                }
            }
            if (!missing.isEmpty()) {
                var usage = EnvironmentFileUsage.read(layout.files(workspace));
                if (usage.fileCount() + usage.directoryCount() + missing.size() > settings.fileCountLimit() * 2L) {
                    throw WorkspacePaths.capacity();
                }
                for (Path directory : missing) {
                    WorkspacePaths.requireInside(layout.root(), directory);
                    Files.createDirectory(directory);
                    changes.created(directory);
                }
            }
            return missing.size();
        });
    }

    @Transactional
    public void upload(AuthContext actor, String path, String name, long length, InputStream input) {
        String logical = child(path, name);
        int limit = Math.toIntExact(Math.min(20L * 1024 * 1024, settings.fileBytes()));
        if (length > limit) {
            throw WorkspacePaths.capacity();
        }
        modify(actor, logical, (workspace, target, changes) -> {
            requireParent(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw exists();
            }
            if (new EnvironmentProjectDirectories(metadata, actor, logical).protectedPath(logical)) {
                throw ApiException.invalidField("name", Messages.text("error.environment.reservedFolderName"));
            }
            var bytes = input.readNBytes(limit + 1);
            var usage = EnvironmentFileUsage.read(layout.files(workspace));
            if (bytes.length > limit || usage.totalBytes() + bytes.length > settings.workspaceBytes()
                || usage.fileCount() >= settings.fileCountLimit()
                || usage.fileCount() + usage.directoryCount() >= settings.fileCountLimit() * 2L) {
                throw WorkspacePaths.capacity();
            }
            Path temporary = Files.createTempFile(layout.control(workspace), "upload-", ".tmp");
            try {
                Files.write(temporary, bytes);
                WorkspacePaths.requireInside(layout.root(), target);
                Files.move(temporary, target);
                changes.created(target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return 1;
        });
    }

    @Transactional
    public void delete(AuthContext actor, String path, boolean directory, String modifiedAt) {
        String logical = WorkspacePaths.projectLogical(path);
        modify(actor, logical, (workspace, target, changes) -> {
            if (new EnvironmentProjectDirectories(metadata, actor, logical).protectedPath(logical)) {
                throw new ApiException(HttpStatus.CONFLICT, "DIRECTORY_IN_USE", Messages.text("error.environment.protectedDirectory"));
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_FILE_MISSING", Messages.text("error.workspace.workspaceFilesystem.bytes.1"));
            }
            if (directory != Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || modifiedAt != null
                && !Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toInstant().toString().equals(modifiedAt)) {
                throw new ApiException(HttpStatus.CONFLICT, "ENVIRONMENT_FILE_CHANGED", Messages.text("error.environment.entryChanged"));
            }
            changes.remove(target);
            return 1;
        });
    }

    private int modify(AuthContext actor, String logical, FileOperation operation) {
        var workspace = metadata.userWorkspace(actor);
        try {
            layout.prepareWorkspace(workspace.getId(), workspace.getInitializedAt() != null);
            var changes = WorkspaceFileChanges.locked(layout, workspace.getId());
            Path target = layout.files(workspace.getId()).resolve(logical);
            WorkspacePaths.requireInside(layout.root(), target);
            int changed = operation.apply(workspace.getId(), target, changes);
            if (changed > 0) {
                layout.recordFileChange(workspace.getId());
                metadata.initializedWorkspace(workspace);
            }
            return changed;
        } catch (FileAlreadyExistsException duplicate) {
            throw exists();
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    private static String child(String path, String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw ApiException.invalidField("name", Messages.text("error.environment.invalidName"));
        }
        String parent = WorkspacePaths.projectLogical(path);
        return WorkspacePaths.projectLogical(parent.isEmpty() ? name : parent + "/" + name);
    }

    private static void requireParent(Path target) {
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw EnvironmentFileService.missingDirectory();
        }
    }

    private static ApiException exists() {
        return ApiException.invalidField("name", Messages.text("error.environment.nameExists"));
    }

    @FunctionalInterface
    private interface FileOperation {
        int apply(String workspace, Path target, WorkspaceFileChanges changes) throws IOException;
    }
}
