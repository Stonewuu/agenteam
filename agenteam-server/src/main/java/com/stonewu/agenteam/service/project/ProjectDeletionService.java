package com.stonewu.agenteam.service.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.project.WorkspaceProjectSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;

@Service
public class ProjectDeletionService {
    private final ProjectMetadataService metadata;
    private final WorkspaceProjectSqlMapper projects;
    private final ConversationSqlMapper conversations;
    private final CollaborationParticipantMapper participants;
    private final ProjectInputFileService inputs;
    private final ProjectWorkspaceLayout layout;
    private final Clock clock;

    public ProjectDeletionService(ProjectMetadataService metadata, WorkspaceProjectSqlMapper projects,
            ConversationSqlMapper conversations, CollaborationParticipantMapper participants,
            ProjectInputFileService inputs, ProjectWorkspaceLayout layout, Clock clock) {
        this.metadata = metadata;
        this.projects = projects;
        this.conversations = conversations;
        this.participants = participants;
        this.inputs = inputs;
        this.layout = layout;
        this.clock = clock;
    }

    @Transactional
    public void delete(AuthContext actor, String id, boolean deleteFiles) {
        var workspace = metadata.userWorkspace(actor);
        var row = projects.lockOwnedProject(actor.enterpriseId(), actor.userId(), id);
        if (row == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        if (participants.exists(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationParticipantRow::getProjectId, id))) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_IN_USE", Messages.text("error.project.collaborationUsesProject"));
        }
        // 只解除空闲对话的关联。有任务同时开始时，回滚整次删除。
        conversations.update(new LambdaUpdateWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getEnterpriseId, actor.enterpriseId()).eq(AgentConversationRow::getUserId, actor.userId())
            .eq(AgentConversationRow::getProjectId, id).isNull(AgentConversationRow::getActiveRunId)
            .set(AgentConversationRow::getProjectId, null).set(AgentConversationRow::getUpdatedAt, clock.instant())
            .setIncrBy(AgentConversationRow::getRevision, 1));
        if (conversations.exists(new LambdaQueryWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getEnterpriseId, actor.enterpriseId()).eq(AgentConversationRow::getUserId, actor.userId())
            .eq(AgentConversationRow::getProjectId, id))) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_BUSY", Messages.text("error.project.activeConversation"));
        }
        var location = new ProjectLocation(workspace.getId(), id, actor.enterpriseId(), actor.userId(), row.getDirectoryPath());
        inputs.release(location);
        if (projects.delete(new LambdaQueryWrapper<WorkspaceProjectRow>().eq(WorkspaceProjectRow::getEnterpriseId, actor.enterpriseId())
            .eq(WorkspaceProjectRow::getUserId, actor.userId()).eq(WorkspaceProjectRow::getId, id)) != 1) {
            throw ResourceAuthorizationService.unavailable();
        }
        try {
            layout.prepareWorkspace(workspace.getId(), workspace.getInitializedAt() != null);
            var changes = WorkspaceFileChanges.locked(layout, workspace.getId());
            changes.remove(layout.registration(location));
            changes.remove(layout.state(location));
            if (deleteFiles) {
                changes.remove(layout.project(location));
                layout.recordFileChange(workspace.getId());
            }
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }
}
