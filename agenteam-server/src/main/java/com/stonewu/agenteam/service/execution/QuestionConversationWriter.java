package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.execution.ExecutionEventMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;

/** 问题作为会话内容保存，刷新或重新连接后仍显示同一个问题和回答。 */
@Service
public class QuestionConversationWriter {
    private final ExecutionMessageMapper messages;
    private final ExecutionEventMapper events;
    private final ToolCallMapper calls;
    private final RunStepMapper steps;
    private final RunQuestionContent content;
    private final Clock clock;

    public QuestionConversationWriter(ExecutionMessageMapper messages, ExecutionEventMapper events, ToolCallMapper calls,
            RunStepMapper steps, RunQuestionContent content, Clock clock) {
        this.messages = messages;
        this.events = events;
        this.calls = calls;
        this.steps = steps;
        this.content = content;
        this.clock = clock;
    }

    public void save(RunRecord run, RunQuestionRow question) {
        var message = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow();
        var call = calls.find(run.enterpriseId(), question.getToolCallId(), false).orElseThrow();
        var blocks = new ArrayList<>(message.blocks());
        String status = "pending".equals(question.getStatus()) ? "waiting_input"
            : "answered".equals(question.getStatus()) ? "completed" : "cancelled";
        String parent = blocks.stream().filter(block -> block.tool() != null && call.stepId().equals(block.stepId()))
            .map(ContentBlock::id).findFirst().orElse(null);
        String id = "question:" + question.getId();
        var existing = blocks.stream().filter(block -> id.equals(block.id())).findFirst();
        int order = existing.map(ContentBlock::displayOrder).orElseGet(() -> Math.max(
            blocks.stream().mapToInt(ContentBlock::displayOrder).max().orElse(0),
            steps.list(run.enterpriseId(), run.id()).stream().mapToInt(RunStepView::displayOrder).max().orElse(0)) + 1);
        var block = new ContentBlock(id, "question", parent, order, question.getRevision().toString(), "", status,
            call.stepId(), null, null, null, Messages.text("text.execution.questionConversationWriter.save.1"), null, null, null, content.view(question));
        blocks.removeIf(value -> id.equals(value.id()));
        blocks.add(block);
        long sequence = Long.parseLong(events.append(run, "block.updated",
            Map.of("messageId", message.id(), "block", block), clock.instant()).sequence());
        for (int index = 0; index < blocks.size(); index++) {
            var value = blocks.get(index);
            if (value.tool() != null && call.stepId().equals(value.stepId())) {
                var updated = value.update(value.text(), status);
                blocks.set(index, updated);
                sequence = Long.parseLong(events.append(run, "block.updated", Map.of("messageId", message.id(), "block", updated), clock.instant()).sequence());
            }
        }
        for (var step : steps.list(run.enterpriseId(), run.id())) {
            if (step.id().equals(call.stepId())) {
                var updated = new RunStepView(step.id(), step.parentStepId(), step.attemptId(), step.kind(), step.title(),
                    step.displayOrder(), status, null, step.startedAt(), "pending".equals(question.getStatus()) ? null : clock.instant().toString(), step.workflow());
                steps.save(run, updated, null, clock.instant());
                sequence = Long.parseLong(events.append(run, "step.updated", updated, clock.instant()).sequence());
            }
        }
        messages.save(run.enterpriseId(), message.id(), message.content(), blocks, "streaming", sequence, clock.instant());
    }
}
