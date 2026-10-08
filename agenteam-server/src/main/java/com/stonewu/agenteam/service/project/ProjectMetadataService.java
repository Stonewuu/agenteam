package com.stonewu.agenteam.service.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.project.ProjectViewMapper;
import com.stonewu.agenteam.mapper.project.UserWorkspaceSqlMapper;
import com.stonewu.agenteam.mapper.project.WorkspaceProjectSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.project.entity.UserWorkspaceRow;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import com.stonewu.agenteam.model.project.request.CreateProjectInput;
import com.stonewu.agenteam.model.project.response.ProjectView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * 数据库维护目录归属；文件系统的准备和迁移由项目文件服务完成。
 */
@Service
public class ProjectMetadataService {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectMetadataService.class);
    private final UserWorkspaceSqlMapper workspaces;
    private final WorkspaceProjectSqlMapper projects;
    private final EnterpriseAuthorizationService authorization;
    private final ListPagination pagination;
    private final Clock clock;

    public ProjectMetadataService(UserWorkspaceSqlMapper workspaces, WorkspaceProjectSqlMapper projects,
                                  EnterpriseAuthorizationService authorization, ListPagination pagination,
                                  Clock clock) {
        this.workspaces = workspaces;
        this.projects = projects;
        this.authorization = authorization;
        this.pagination = pagination;
        this.clock = clock;
    }

    public PageResponse<ProjectView> list(AuthContext actor, String query, String cursor, Integer limit) {
        authorization.require(actor, "workspace.view");
        String search = query == null ? "" : query.strip();
        if (search.length() > 100) {
            throw ApiException.invalidField("query", "搜索内容不能超过 100 个字符。");
        }
        int size = pagination.limit(limit);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "projects", search,
            "created_desc");
        var after = pagination.read(cursor, binding);
        var criteria = owned(actor.enterpriseId(), actor.userId())
            .like(!search.isEmpty(), WorkspaceProjectRow::getName, search)
            .orderByDesc(WorkspaceProjectRow::getCreatedAt, WorkspaceProjectRow::getId);
        if (after != null) {
            criteria.and(group -> group.lt(WorkspaceProjectRow::getCreatedAt, after.time())
                .or(other -> other.eq(WorkspaceProjectRow::getCreatedAt, after.time())
                    .lt(WorkspaceProjectRow::getId, after.id())));
        }
        var rows = projects.selectPage(new Page<WorkspaceProjectRow>(1, size + 1, false), criteria).getRecords();
        var values = rows.stream().map(ProjectViewMapper::view).toList();
        return pagination.page(values, size, binding,
            row -> new PagePosition(Instant.parse(row.createdAt()), row.id(), null));
    }

    public ProjectView view(AuthContext actor, String id) {
        authorization.require(actor, "workspace.view");
        return ProjectViewMapper.view(require(actor.enterpriseId(), actor.userId(), id));
    }

    public WorkspaceProjectRow require(String enterprise, String user, String id) {
        var found = projects.selectOne(owned(enterprise, user).eq(WorkspaceProjectRow::getId, id));
        if (found == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        return found;
    }

    public UserWorkspaceRow workspace(String enterprise, String user, String id) {
        var found = workspaces.selectOne(
            new LambdaQueryWrapper<UserWorkspaceRow>().eq(UserWorkspaceRow::getEnterpriseId, enterprise)
                .eq(UserWorkspaceRow::getUserId, user).eq(UserWorkspaceRow::getId, id));
        if (found == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        return found;
    }

    @Transactional
    public ProjectView create(AuthContext actor, CreateProjectInput input) {
        authorization.lockAndRequire(actor, "agent.run");
        InputValidation.validate(input);
        return ProjectViewMapper.view(
            createOwned(actor.enterpriseId(), actor.userId(), input.name(), input.directory(), null));
    }

    @Transactional
    public WorkspaceProjectRow createOwned(String enterprise, String user, String name, String directory,
                                           String legacyConversation) {
        ensureWorkspace(enterprise, user);
        var workspace = workspaces.lockOwned(enterprise, user);
        if (workspace == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        String id = UUID.randomUUID().toString();
        String path = ProjectPaths.directory(directory, id);
        String normalized = ProjectPaths.directoryKey(path);
        var parents = new ArrayList<String>();
        String parent = normalized;
        while (parent.contains("/")) {
            parents.add(parent);
            parent = parent.substring(0, parent.lastIndexOf('/'));
        }
        String prefix = normalized.replace("=", "==").replace("%", "=%").replace("_", "=_") + "/%";
        if (projects.conflictingDirectory(workspace.getId(), parents, prefix) != null) {
            throw ApiException.invalidField("directory", "此目录已由其他项目使用，请选择已有项目或另填目录。");
        }
        String title = name == null || name.isBlank() ? "新项目" : name.strip();
        if (title.length() > 100) {
            throw ApiException.invalidField("name", "项目名称不能超过 100 个字符。");
        }
        var row = new WorkspaceProjectRow();
        row.setId(id);
        row.setEnterpriseId(enterprise);
        row.setUserId(user);
        row.setWorkspaceId(workspace.getId());
        row.setName(title);
        row.setDirectoryPath(path);
        row.setDirectoryKey(ProjectPaths.directoryKey(path));
        row.setLegacyConversationId(legacyConversation);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        projects.insert(row);
        return row;
    }

    private void ensureWorkspace(String enterprise, String user) {
        String id = ProjectPaths.workspaceId(enterprise, user);
        var existing = workspaces.selectById(id);
        if (existing != null) {
            if (!Objects.equals(enterprise, existing.getEnterpriseId()) || !Objects.equals(user,
                existing.getUserId())) {
                throw ResourceAuthorizationService.unavailable();
            }
            return;
        }
        var row = new UserWorkspaceRow();
        row.setId(id);
        row.setEnterpriseId(enterprise);
        row.setUserId(user);
        row.setDirectoryPath(id);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        try {
            workspaces.insert(row);
        } catch (DuplicateKeyException concurrent) {
            LOG.debug("用户工作空间已由并发请求创建，工作空间编号 {}", id, concurrent);
        }
    }

    public void initialized(UserWorkspaceRow workspace, WorkspaceProjectRow project) {
        var now = clock.instant();
        int workspaceChanged = workspaces.update(
            new LambdaUpdateWrapper<UserWorkspaceRow>().eq(UserWorkspaceRow::getId, workspace.getId())
                .eq(UserWorkspaceRow::getEnterpriseId, workspace.getEnterpriseId())
                .eq(UserWorkspaceRow::getUserId, workspace.getUserId())
                .isNull(UserWorkspaceRow::getInitializedAt).set(UserWorkspaceRow::getInitializedAt, now));
        if (workspaceChanged != 1 && workspace(workspace.getEnterpriseId(), workspace.getUserId(),
            workspace.getId()).getInitializedAt() == null) {
            throw new IllegalStateException("用户工作空间初始化结果未保存");
        }
        int projectChanged = projects.update(
            new LambdaUpdateWrapper<WorkspaceProjectRow>().eq(WorkspaceProjectRow::getId, project.getId())
                .eq(WorkspaceProjectRow::getEnterpriseId, project.getEnterpriseId())
                .eq(WorkspaceProjectRow::getUserId, project.getUserId())
                .isNull(WorkspaceProjectRow::getInitializedAt).set(WorkspaceProjectRow::getInitializedAt, now));
        if (projectChanged != 1 && require(project.getEnterpriseId(), project.getUserId(),
            project.getId()).getInitializedAt() == null) {
            throw new IllegalStateException("项目目录初始化结果未保存");
        }
    }

    private static LambdaQueryWrapper<WorkspaceProjectRow> owned(String enterprise, String user) {
        return new LambdaQueryWrapper<WorkspaceProjectRow>().eq(WorkspaceProjectRow::getEnterpriseId, enterprise)
            .eq(WorkspaceProjectRow::getUserId, user);
    }

}
