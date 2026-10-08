package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.model.execution.entity.ConversationQueryRow;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.execution.entity.ToolApprovalPolicy;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 私有会话存储；活动执行的修改由调用方在锁定会话后完成。
 */
@Repository
public class ConversationMapper {
    private final ConversationSqlMapper statements;

    public ConversationMapper(ConversationSqlMapper statements) {
        this.statements = statements;
    }

    public Optional<ConversationRecord> find(String enterprise, String user, String id, boolean lock) {
        return statements.findConversation(enterprise, user, id, lock).stream().map(this::map).findFirst();
    }

    public void create(String id, String enterprise, String user, String agent, String version, String hire,
                       String title, String mode, Instant now) {
        create(id, enterprise, user, agent, version, hire, title, mode, ToolApprovalPolicy.DEFAULT.value(), now);
    }

    public void create(String id, String enterprise, String user, String agent, String version, String hire,
                       String title, String mode, String approvalPolicy, Instant now) {
        statements.createAgentConversation(id, enterprise, user, agent, version, hire, title, mode, approvalPolicy,
            timestamp(now));
    }

    public void createWorkflowPreview(String id, String enterprise, String user, String workflow, String title,
                                      Instant now) {
        statements.createWorkflowPreviewAgentConversation(id, enterprise, user, workflow, title, timestamp(now));
    }

    public record StreamPosition(String status, String activeRunId, long lastSequence) {
    }

    public Optional<StreamPosition> streamPosition(String enterprise, String user, String id, Instant oldestPreview) {
        return statements.streamPositionAgentConversation(enterprise, user, id, timestamp(oldestPreview)).stream()
            .map(row -> new StreamPosition(row.getStatus(), row.getActiveRunId(), row.getLastSequence())).findFirst();
    }

    public long lastSequence(String enterprise, String id) {
        return DataAccessUtils.nullableSingleResult(statements.lastSequenceAgentConversation(enterprise, id));
    }

    public String eventDeliveryMode(String enterprise, String id) {
        return statements.eventDeliveryMode(enterprise, id);
    }

    public List<ConversationRecord> list(String enterprise, String user, String status, Boolean favorite,
                                         String agentId, String query, PagePosition after, int limit,
                                         Instant oldestDeleted) {
        return statements.listConversations(enterprise, user, status, favorite, agentId, query, after, limit,
                oldestDeleted)
            .stream().map(this::map).toList();
    }

    public void activate(String enterprise, String id, String run, Instant now) {
        int changed = statements.activateAgentConversation(run, timestamp(now), enterprise, id);
        if (changed != 1) {
            throw new IllegalStateException("会话已被其他执行占用，当前提交未保存");
        }
    }

    public void finish(String enterprise, String conversation, String run, Instant now) {
        statements.finishAgentConversation(timestamp(now), enterprise, conversation, run);
    }

    public long advanceSequence(String enterprise, String conversation, Instant now) {
        statements.advanceSequenceAgentConversation(timestamp(now), enterprise, conversation);
        return DataAccessUtils.nullableSingleResult(statements.readConversationSequence(enterprise, conversation));
    }

    public void update(ConversationRecord row, String title, Boolean favorite, String status, Instant now) {
        update(row, title, favorite, status, null, now);
    }

    public void update(ConversationRecord row, String title, Boolean favorite, String status, String approvalPolicy,
                       Instant now) {
        int changed = statements.updateAgentConversation(title, favorite, status, approvalPolicy, timestamp(now),
            row.enterpriseId(), row.userId(), row.id(), row.revision());
        if (changed != 1) {
            throw ApiException.versionConflict(row.revision());
        }
    }

    private ConversationRecord map(ConversationQueryRow row) {
        return new ConversationRecord(row.getId(), row.getEnterpriseId(), row.getUserId(),
            row.getAgentId(), row.getAgentVersionId(), row.getHireId(), row.getTitle(),
            row.getAgentName(), row.getAgentIcon(), row.getAgentColor(), row.getMode(), row.getStatus(),
            row.getFavorite(),
            row.getActiveRunId(), row.getLastSequence(), row.getRevision(), instant(row.getDeletedAt()),
            instant(row.getCreatedAt()), instant(row.getUpdatedAt()), row.getPreviewResourceId(),
            ToolApprovalPolicy.from(row.getApprovalPolicy()).value(),
            row.getModelProfileId() == null ? null : new ModelSelection(row.getModelProfileId(),
                row.getReasoningEffort()), row.getProjectId(), row.getEventDeliveryMode());
    }

    public void selectModel(ConversationRecord row, ModelSelection selection, Instant now) {
        int changed = statements.selectModel(row.enterpriseId(), row.userId(), row.id(), row.revision(), selection,
            now);
        if (changed != 1) {
            throw ApiException.versionConflict(row.revision());
        }
    }

    public void selectProject(ConversationRecord row, String projectId, boolean initial, Instant now) {
        int changed = statements.selectProject(row.enterpriseId(), row.userId(), row.id(), row.revision(), projectId,
            initial, now);
        if (changed != 1) {
            throw ApiException.versionConflict(row.revision());
        }
    }

    public void enableLiveEvents(ConversationRecord row) {
        if (!row.liveEvents() && statements.enableLiveEvents(row.enterpriseId(), row.userId(), row.id()) != 1) {
            throw new IllegalStateException("对话仍有进行中的执行，不能切换事件读取方式");
        }
    }
}
