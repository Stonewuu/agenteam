package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.project.ProjectViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.response.ConversationView;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import com.stonewu.agenteam.model.project.response.ProjectView;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;

/**
 * 会话只引用项目，切换引用不移动项目文件，也不删除原项目。
 */
@Service
public class ConversationProjectService {
    private final ConversationMapper conversations;
    private final ProjectMetadataService projects;
    private final EnterpriseAuthorizationService authorization;
    private final ConversationQueryService queries;
    private final Clock clock;

    public ConversationProjectService(ConversationMapper conversations, ProjectMetadataService projects,
                                      EnterpriseAuthorizationService authorization, ConversationQueryService queries,
                                      Clock clock) {
        this.conversations = conversations;
        this.projects = projects;
        this.authorization = authorization;
        this.queries = queries;
        this.clock = clock;
    }

    @Transactional
    public ConversationRecord initial(String enterprise, String user, String conversation, String selectedProject) {
        var row = owned(enterprise, user, conversation, true);
        if (row.projectId() == null) {
            var project = selectedProject == null ? projects.createOwned(enterprise, user, row.title(), null,
                conversation)
                : projects.require(enterprise, user, selectedProject);
            conversations.selectProject(row, project.getId(), true, clock.instant());
            row = owned(enterprise, user, conversation, false);
        }
        return row;
    }

    @Transactional
    public WorkspaceProjectRow ensure(String enterprise, String user, String conversation) {
        var row = initial(enterprise, user, conversation, null);
        return projects.require(enterprise, user, row.projectId());
    }

    @Transactional
    public ProjectView current(AuthContext actor, String conversation) {
        authorization.require(actor, "conversation.view");
        return ProjectViewMapper.view(ensure(actor.enterpriseId(), actor.userId(), conversation));
    }

    public void authorize(AuthContext actor, String conversation) {
        authorization.lockAndRequire(actor, "agent.run");
        owned(actor.enterpriseId(), actor.userId(), conversation, true);
    }

    @Transactional
    public ConversationView select(AuthContext actor, String conversation, String projectId, long revision) {
        authorize(actor, conversation);
        var row = owned(actor.enterpriseId(), actor.userId(), conversation, true);
        RunSubmissionService.active(row);
        if (row.revision() != revision) {
            throw ApiException.versionConflict(row.revision());
        }
        if (row.activeRunId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_BUSY",
                "请等待当前任务结束，或先停止任务，再切换项目。");
        }
        var project = projects.require(actor.enterpriseId(), actor.userId(), projectId);
        if (!project.getId().equals(row.projectId())) {
            conversations.selectProject(row, project.getId(), false, clock.instant());
        }
        return queries.view(actor, owned(actor.enterpriseId(), actor.userId(), conversation, false), new HashMap<>());
    }

    private ConversationRecord owned(String enterprise, String user, String id, boolean lock) {
        return conversations.find(enterprise, user, id, lock)
            .filter(row -> row.mode().equals("normal") && !row.status().equals("deleted"))
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }
}
