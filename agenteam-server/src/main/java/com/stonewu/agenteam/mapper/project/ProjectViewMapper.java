package com.stonewu.agenteam.mapper.project;

import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import com.stonewu.agenteam.model.project.response.ProjectView;

/**
 * 将项目存储记录转换为公开目录信息。
 */
public final class ProjectViewMapper {
    private ProjectViewMapper() {
    }

    public static ProjectView view(WorkspaceProjectRow row) {
        return new ProjectView(row.getId(), row.getName(), row.getDirectoryPath(), row.getCreatedAt().toString(),
            row.getUpdatedAt().toString());
    }
}
