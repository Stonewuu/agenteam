package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 容器路径以用户空间为根，命令相对路径以当前项目为起点。
 */
public final class ProjectExecutionPaths {
    public static final String ROOT = "/workspace";

    private ProjectExecutionPaths() {
    }

    public static String projectDirectory(String directory) {
        return ROOT + "/" + directory;
    }

    public static Path workingDirectory(ProjectWorkspaceLayout layout, ProjectLocation project,
                                        String requested) throws IOException {
        Path root = layout.files(project.workspaceId());
        Path current = layout.project(project);
        if (requested == null || requested.isBlank()) {
            return current;
        }
        if (requested.length() > 1024 || requested.contains("\\") || requested.contains(":")
            || requested.codePoints().anyMatch(Character::isISOControl)) {
            throw WorkspacePaths.invalid();
        }
        Path target;
        if (requested.equals(ROOT) || requested.startsWith(ROOT + "/")) {
            String relative = requested.length() == ROOT.length() ? "" : requested.substring(ROOT.length() + 1);
            target = root.resolve(relative).normalize();
        } else if (requested.startsWith("/")) {
            throw WorkspacePaths.invalid();
        } else {
            target = current.resolve(requested).normalize();
        }
        WorkspacePaths.requireInside(root, target);
        WorkspacePaths.projectLogical(root.relativize(target).toString().replace('\\', '/'));
        return target;
    }

    public static String containerDirectory(ProjectWorkspaceLayout layout, ProjectLocation project, Path directory) {
        String relative = layout.files(project.workspaceId()).relativize(directory).toString().replace('\\', '/');
        return relative.isEmpty() ? ROOT : ROOT + "/" + relative;
    }
}
