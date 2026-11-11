package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunQuestionView;
import com.stonewu.agenteam.service.chat.ChatQuestionMessages;
import com.stonewu.agenteam.service.execution.RunQuestionChannel;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 协作任务的问题沿用同一次执行，回答不会变成新一轮任务要求。 */
@Service
public class CollaborationRunQuestionChannel implements RunQuestionChannel {
    private final CollaborationRunAccessService access;
    private final CollaborationWorkService works;
    private final CollaborationTaskMapper tasks;
    private final ChatQuestionMessages messages;
    private final CollaborationDependencyService dependencies;

    public CollaborationRunQuestionChannel(CollaborationRunAccessService access, CollaborationWorkService works,
            CollaborationTaskMapper tasks, ChatQuestionMessages messages, CollaborationDependencyService dependencies) {
        this.dependencies = dependencies;
        this.access = access;
        this.works = works;
        this.tasks = tasks;
        this.messages = messages;
    }

    @Override
    public void initialize(RunRecord run, RunQuestionRow question) {
        if (!access.applies(run)) {
            return;
        }
        var context = access.require(run, run.conversationId(), false);
        question.setTaskId(context.task().getId());
        question.setChatConversationId(context.task().getConversationId());
        question.setBotBindingId(context.participant().getBotBindingId());
        if (context.task().getDeadlineAt().isBefore(question.getExpiresAt())) {
            question.setExpiresAt(context.task().getDeadlineAt());
        }
    }

    @Override
    public void created(RunRecord run, RunQuestionRow question, RunQuestionView view) {
        if (question.getTaskId() == null) {
            return;
        }
        var context = access.require(run, run.conversationId(), false);
        works.change(context.work(), "waiting_input");
        if (context.work().getId().equals(context.task().getOwnerWorkId())) {
            taskStatus(context.task(), "waiting_input");
        }
        if (!context.task().fromWebRoom()) {
            messages.send(question, view);
        }
    }

    @Override
    public void resumed(RunRecord run) {
        if (!access.applies(run) || !"queued".equals(run.status())) {
            return;
        }
        var context = access.require(run, run.conversationId(), false);
        works.change(context.work(), "running");
        if (context.work().getId().equals(context.task().getOwnerWorkId())) {
            taskStatus(context.task(), "active");
        }
    }

    private void taskStatus(CollaborationTaskRow task, String status) {
        boolean blocked = "blocked".equals(task.getStatus());
        if (blocked && !dependencies.failureReplaced(task)) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_TASK_BLOCKED", Messages.text("error.collaboration.collaborationRunQuestionChannel.taskStatus.1"));
        }
        if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>()
                .eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId()).eq(CollaborationTaskRow::getId, task.getId())
                .eq(blocked, CollaborationTaskRow::getStatus, "blocked")
                .in(!blocked, CollaborationTaskRow::getStatus, "active", "waiting_input", "waiting_approval")
                .set(blocked, CollaborationTaskRow::getErrorCode, null)
                .set(CollaborationTaskRow::getStatus, status).setIncrBy(CollaborationTaskRow::getRevision, 1)) != 1) {
            throw new IllegalStateException(Messages.text("error.collaboration.collaborationRunQuestionChannel.taskStatus.2"));
        }
    }
}
