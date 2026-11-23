package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 在任务结束事务内保存未完成步骤和已生成内容，正文未改变时不重复更新。
 */
@Service
public class RunOutputWriter {
    private final ExecutionMessageMapper messages;
    private final RunStepMapper steps;
    private final ConversationChangePublisher changes;
    private final LivePartialOutputRecovery partial;
    private final Clock clock;

    public RunOutputWriter(ExecutionMessageMapper messages, RunStepMapper steps, ConversationChangePublisher changes,
                           LivePartialOutputRecovery partial, Clock clock) {
        this.messages = messages;
        this.steps = steps;
        this.changes = changes;
        this.partial = partial;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(RunRecord run, String status) {
        for (var step : steps.list(run.enterpriseId(), run.id())) {
            if (!List.of("pending", "running", "waiting_approval", "waiting_input").contains(step.status())) {
                continue;
            }
            var finished = new RunStepView(step.id(), step.parentStepId(), step.attemptId(), step.kind(), step.title(),
                step.displayOrder(), status, step.publicSummary(), step.startedAt(), clock.instant().toString(),
                step.workflow());
            steps.save(run, finished, null, clock.instant());
            changes.append(run, "step.updated", finished, clock.instant());
        }
        var stored = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow();
        var output = partial.merge(run, stored);
        var blocks = output.blocks().stream()
            .map(block -> List.of("pending", "running", "waiting_approval", "waiting_input").contains(block.status())
                ? block.update(block.text(), status) : block).toList();
        for (var block : blocks) {
            if (stored.blocks().stream().noneMatch(block::equals)) {
                changes.append(run, "block.updated", Map.of("messageId", output.id(), "block", block), clock.instant());
            }
        }
        if (!stored.blocks().equals(blocks) || !stored.content().equals(output.content())) {
            messages.save(run.enterpriseId(), output.id(), output.content(), blocks, status, run.lastSequence(), clock.instant());
        }
    }
}
