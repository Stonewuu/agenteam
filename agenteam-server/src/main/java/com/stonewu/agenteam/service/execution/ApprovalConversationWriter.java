package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ExecutionEventMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.RunApprovalRecord;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ContentBlock.ToolBlockDetails;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * 确认卡片和步骤保存在正式消息中，刷新页面后仍可读取相同决定。
 */
@Service
public class ApprovalConversationWriter {
    private final ExecutionMessageMapper messages;
    private final ExecutionEventMapper events;
    private final RunStepMapper steps;
    private final Clock clock;
    private final ToolCallMapper calls;

    public ApprovalConversationWriter(ExecutionMessageMapper messages, ExecutionEventMapper events, RunStepMapper steps,
                                      Clock clock, ToolCallMapper calls) {
        this.messages = messages;
        this.events = events;
        this.steps = steps;
        this.clock = clock;
        this.calls = calls;
    }

    public void save(RunRecord run, RunApprovalRecord approval) {
        var message = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow();
        var blocks = new ArrayList<>(message.blocks());
        boolean pending = approval.status().equals("pending");
        boolean rejected = approval.status().equals("rejected");
        boolean workflow = approval.toolCallId() == null;
        String status = pending ? "waiting_approval" : approval.status()
            .equals("approved") || (workflow && rejected) ? "completed" : "failed";
        long sequence = run.lastSequence();
        ContentBlock card = blocks.stream()
            .filter(block -> approval.id().equals(block.approvalId()) && block.type().equals("approval")).findFirst()
            .orElse(null);
        if (card == null) {
            int order = Math.max(blocks.stream().mapToInt(ContentBlock::displayOrder).max().orElse(0),
                steps.list(run.enterpriseId(), run.id()).stream().mapToInt(RunStepView::displayOrder).max()
                    .orElse(0)) + 1;
            var target = blocks.stream().filter(block -> approval.stepId().equals(block.stepId())).findFirst()
                .orElse(null);
            String parent = target == null ? null : target.type()
                .equals("workflow") ? target.id() : target.parentBlockId();
            card = new ContentBlock(UUID.randomUUID().toString(), "approval", parent, order, "1",
                approval.summary().description(), status,
                approval.stepId(), approval.id(), null, null, approval.summary().title(), null);
            blocks.add(card);
        } else {
            int index = blocks.indexOf(card);
            card = card.update(card.text(), status);
            blocks.set(index, card);
        }
        sequence = Long.parseLong(
            events.append(run, "block.updated", Map.of("messageId", message.id(), "block", card), clock.instant())
                .sequence());
        String toolStatus = workflow ? null : pending ? "waiting_approval" : rejected ? "skipped" : null;
        var call = toolStatus == null ? null : calls.find(run.enterpriseId(), approval.toolCallId(), false)
            .orElseThrow();
        if (toolStatus != null) {
            for (int i = 0; i < blocks.size(); i++) {
                var block = blocks.get(i);
                if (block.type().equals("tool") && approval.stepId().equals(block.stepId())) {
                    var updated = block.update(block.text(), toolStatus);
                    var tool = updated.tool();
                    updated = new ContentBlock(updated.id(), updated.type(), updated.parentBlockId(),
                        updated.displayOrder(), updated.revision(), updated.text(),
                        updated.status(), updated.stepId(), updated.approvalId(), updated.file(), updated.citation(),
                        updated.label(),
                        new ToolBlockDetails(tool.toolCallId(), tool.name(), tool.sourceKind(),
                            call.requestRedacted().toString(),
                            rejected ? "用户已拒绝本次操作，未发送请求。" : tool.result(), tool.callStatus(),
                            toolStatus));
                    blocks.set(i, updated);
                    sequence = Long.parseLong(
                        events.append(run, "block.updated", Map.of("messageId", message.id(), "block", updated),
                            clock.instant()).sequence());
                }
            }
        }
        if (toolStatus != null) {
            for (var step : steps.list(run.enterpriseId(), run.id())) {
                if (step.id().equals(approval.stepId())) {
                    var waiting = new RunStepView(step.id(), step.parentStepId(), step.attemptId(), step.kind(),
                        step.title(), step.displayOrder(), toolStatus, null, step.startedAt(),
                        pending ? null : clock.instant().toString(), step.workflow());
                    steps.save(run, waiting, null, clock.instant());
                    sequence = Long.parseLong(events.append(run, "step.updated", waiting, clock.instant()).sequence());
                }
            }
        }
        messages.save(run.enterpriseId(), message.id(), message.content(), blocks, "streaming", sequence,
            clock.instant());
    }
}
