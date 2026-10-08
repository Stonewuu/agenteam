package com.stonewu.agenteam.service.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.project.WorkspaceProjectFileSqlMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectFileRow;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.Utf8Text;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 项目中的上传资料保留原文件记录及访问归属，不依赖原会话的状态。
 */
@Service
public class ProjectInputFileService {
    private final WorkspaceProjectFileSqlMapper references;
    private final FileSqlMapper files;
    private final Clock clock;

    public ProjectInputFileService(WorkspaceProjectFileSqlMapper references, FileSqlMapper files, Clock clock) {
        this.references = references;
        this.files = files;
        this.clock = clock;
    }

    public boolean contains(String enterprise, String user, String file) {
        return references.exists(
            new LambdaQueryWrapper<WorkspaceProjectFileRow>().eq(WorkspaceProjectFileRow::getEnterpriseId, enterprise)
                .eq(WorkspaceProjectFileRow::getUserId, user).eq(WorkspaceProjectFileRow::getFileId, file));
    }

    @Transactional
    public void retain(ProjectLocation project, Set<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        var missing = new LinkedHashSet<>(ids);
        references.selectList(
            new LambdaQueryWrapper<WorkspaceProjectFileRow>().eq(WorkspaceProjectFileRow::getEnterpriseId,
                    project.enterpriseId())
                .eq(WorkspaceProjectFileRow::getUserId, project.userId())
                .eq(WorkspaceProjectFileRow::getProjectId, project.projectId())
                .in(WorkspaceProjectFileRow::getFileId, ids)).forEach(row -> missing.remove(row.getFileId()));
        if (missing.isEmpty()) {
            return;
        }
        var now = clock.instant();
        if (references.lockReadyInputs(project.enterpriseId(), project.userId(), missing, now)
            .size() != missing.size()) {
            throw FileAccessService.unavailable();
        }
        var rows = missing.stream().map(id -> {
            var row = new WorkspaceProjectFileRow();
            row.setId(Utf8Text.revision(project.projectId(), id));
            row.setEnterpriseId(project.enterpriseId());
            row.setUserId(project.userId());
            row.setProjectId(project.projectId());
            row.setFileId(id);
            row.setCreatedAt(now);
            return row;
        }).toList();
        references.insert(rows, 100);
        int changed = files.update(
            new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, project.enterpriseId())
                .eq(FileObjectRow::getOwnerUserId, project.userId()).in(FileObjectRow::getId, missing)
                .eq(FileObjectRow::getPurpose, "attachment")
                .eq(FileObjectRow::getStatus, "ready").isNull(FileObjectRow::getDeletedAt)
                .set(FileObjectRow::getExpiresAt, null).set(FileObjectRow::getUpdatedAt, now));
        if (changed != missing.size()) {
            throw new IllegalStateException("项目资料保留记录未能完整保存");
        }
    }
}
