package com.stonewu.agenteam.mapper.execution;


import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.mapper.memory.MemoryMapper;
import com.stonewu.agenteam.mapper.todo.TodoSourceMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 只删除已结束且超过七天的预览结果，次数记录保留供用量核对。
 */
@Repository
public class PreviewRetentionMapper {
    public record Preview(String enterprise, String user, String conversation) {
    }

    private final PreviewRetentionSqlMapper statements;
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final MessageAttachmentMapper attachments;
    private final EnterpriseMapper enterprises;
    private final TodoSourceMapper todos;
    private final MemoryMapper memories;

    private final RunApprovalSqlMapper runApprovalSqlMapper;

    private final ToolCallSqlMapper toolCallSqlMapper;

    private final ExecutionEventSqlMapper executionEventSqlMapper;

    private final RunStepSqlMapper runStepSqlMapper;

    private final RunCheckpointSqlMapper runCheckpointSqlMapper;

    private final RunAttemptSqlMapper runAttemptSqlMapper;

    private final RunJobSqlMapper runJobSqlMapper;

    private final ExecutionMessageSqlMapper executionMessageSqlMapper;

    private final RunSqlMapper runSqlMapper;

    public PreviewRetentionMapper(PreviewRetentionSqlMapper statements, ConversationMapper conversations,
                                  RunMapper runs, MessageAttachmentMapper attachments, EnterpriseMapper enterprises,
                                  TodoSourceMapper todos, MemoryMapper memories,
                                  RunApprovalSqlMapper runApprovalSqlMapper, ToolCallSqlMapper toolCallSqlMapper,
                                  ExecutionEventSqlMapper executionEventSqlMapper, RunStepSqlMapper runStepSqlMapper,
                                  RunCheckpointSqlMapper runCheckpointSqlMapper,
                                  RunAttemptSqlMapper runAttemptSqlMapper, RunJobSqlMapper runJobSqlMapper,
                                  ExecutionMessageSqlMapper executionMessageSqlMapper, RunSqlMapper runSqlMapper) {
        this.runSqlMapper = runSqlMapper;
        this.executionMessageSqlMapper = executionMessageSqlMapper;
        this.runJobSqlMapper = runJobSqlMapper;
        this.runAttemptSqlMapper = runAttemptSqlMapper;
        this.runCheckpointSqlMapper = runCheckpointSqlMapper;
        this.runStepSqlMapper = runStepSqlMapper;
        this.executionEventSqlMapper = executionEventSqlMapper;
        this.toolCallSqlMapper = toolCallSqlMapper;
        this.runApprovalSqlMapper = runApprovalSqlMapper;
        this.statements = statements;
        this.conversations = conversations;
        this.runs = runs;
        this.attachments = attachments;
        this.enterprises = enterprises;
        this.todos = todos;
        this.memories = memories;
    }

    public List<Preview> expired(Instant before) {
        return statements.expiredAgentConversation(timestamp(before)).stream()
            .map(row -> new Preview(row.getEnterpriseId(), row.getUserId(), row.getId())).toList();
    }

    @Transactional
    public void remove(Preview preview, Instant before) {
        if (enterprises.lockEnterprise(preview.enterprise()).isEmpty()) {
            return;
        }
        var row = conversations.find(preview.enterprise(), preview.user(), preview.conversation(), true).orElse(null);
        if (row == null || !row.mode().equals("preview") || row.activeRunId() != null || row.createdAt()
            .isAfter(before)) {
            return;
        }
        var executions = runs.forConversation(preview.enterprise(), preview.conversation());
        if (executions.stream().anyMatch(run -> !run.terminal())) {
            return;
        }
        todos.clearConversation(preview.enterprise(), preview.conversation());
        memories.clearSourceConversation(preview.enterprise(), preview.conversation());
        for (var run : executions) {
            runApprovalSqlMapper.removeRunApproval(run.enterpriseId(), run.id());
            toolCallSqlMapper.removeToolCall(run.enterpriseId(), run.id());
            executionEventSqlMapper.removeAgentEvent(run.enterpriseId(), run.id());
            runStepSqlMapper.removeRunStep(run.enterpriseId(), run.id());
            runStepSqlMapper.deleteRunSteps(run.enterpriseId(), run.id());
            runCheckpointSqlMapper.deleteRunCheckpoints(run.enterpriseId(), run.id());
            runAttemptSqlMapper.removeRunAttempt(run.enterpriseId(), run.id());
            runJobSqlMapper.removeBackgroundJob(run.enterpriseId(), "run:" + run.id());
        }
        statements.removeMessageFeedback(preview.enterprise(), preview.conversation());
        executionMessageSqlMapper.removeAgentMessage(preview.enterprise(), preview.conversation());
        runSqlMapper.removeAgentRun(preview.enterprise(), preview.conversation());
        attachments.removeConversation(preview.enterprise(), preview.conversation(), before.plusSeconds(7 * 86400));
        executionMessageSqlMapper.deleteConversationMessages(preview.enterprise(), preview.conversation());
        statements.removeAgentConversation(preview.enterprise(), preview.conversation());
    }
}
