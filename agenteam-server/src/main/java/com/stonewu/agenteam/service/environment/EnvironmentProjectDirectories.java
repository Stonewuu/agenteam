package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import com.stonewu.agenteam.service.project.ProjectPaths;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 项目名按登记的完整路径匹配；保留项目的上级目录和标准子目录。 */
final class EnvironmentProjectDirectories {
    private final Map<String, String> names = new HashMap<>();
    private final Set<String> protectedPaths = new HashSet<>(Set.of("", "projects", "environment", "environment/work"));

    EnvironmentProjectDirectories(ProjectMetadataService projects, AuthContext actor, String path) {
        String key = ProjectPaths.directoryKey(path);
        if (key.equals("projects") || key.startsWith("projects/")) {
            for (var row : projects.directoryNames(actor)) {
                String directory = row.getDirectoryPath();
                names.put(directory, row.getName());
                String parent = ProjectPaths.directoryKey(directory);
                for (String area : WorkspacePaths.ROOTS) {
                    protectedPaths.add(parent + "/" + area);
                }
                while (parent.contains("/")) {
                    protectedPaths.add(parent);
                    parent = parent.substring(0, parent.lastIndexOf('/'));
                }
            }
        }
    }

    String projectName(String path) {
        return names.get(path);
    }

    boolean protectedPath(String path) {
        return protectedPaths.contains(ProjectPaths.directoryKey(path));
    }
}
