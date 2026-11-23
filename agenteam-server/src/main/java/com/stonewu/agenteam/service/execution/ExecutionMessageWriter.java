package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每轮完整内容与必要步骤在同一事务保存，正文未改变时不重复更新消息。
 */
@Service
public class ExecutionMessageWriter {
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final ExecutionMessageMapper messages;
    private final ConversationChangePublisher events;
    private final RunStepMapper steps;
    private final RunLifecycleService lifecycle;
    private final Clock clock;
    private final RunJobMapper jobs;
    private final ResourceJson json;

    public ExecutionMessageWriter(ConversationMapper conversations, RunMapper runs, ExecutionMessageMapper messages,
                                  ConversationChangePublisher events, RunStepMapper steps, RunLifecycleService lifecycle,
                                  Clock clock,
                                  RunJobMapper jobs, ResourceJson json) {
        this.conversations = conversations;
        this.runs = runs;
        this.messages = messages;
        this.events = events;
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.jobs = jobs;
        this.json = json;
    }

    @Transactional
    public void save(JobLease lease, String batchId, List<ExecutionChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        var found = runs.find(lease.enterpriseId(), lease.runId(), false).orElseThrow();
        conversations.find(lease.enterpriseId(), lease.userId(), found.conversationId(), true).orElseThrow();
        var run = runs.find(lease.enterpriseId(), lease.runId(), true).orElseThrow();
        lifecycle.requireLease(run, lease);
        String hash = json.hash(json.tree(changes));
        var applied = jobs.lastEventBatch(lease);
        if (batchId.equals(applied.id())) {
            if (!hash.equals(applied.hash())) {
                throw new IllegalStateException(Messages.text("error.execution.executionMessageWriter.save.1"));
            }
            return;
        }
        var output = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow();
        Map<String, ContentBlock> blocks = new LinkedHashMap<>();
        output.blocks().forEach(block -> blocks.put(block.id(), block));
        long sequence = run.lastSequence();
        boolean contentChanged = false;
        for (var change : changes) {
            if (change.step() != null) {
                steps.save(run, change.step(), change.input(), change.result(), clock.instant());
                sequence = Long.parseLong(
                    events.append(run, "step.updated", change.step(), clock.instant()).sequence());
            } else {
                var block = change.block();
                var previous = blocks.get(block.id());
                if (block.equals(previous)) {
                    continue;
                }
                long expected = previous == null ? 1 : Math.addExact(Long.parseLong(previous.revision()), 1);
                if (change.delta() != null || change.baseRevision() == null
                    || !change.baseRevision().equals(previous == null ? "0" : previous.revision())
                    || Long.parseLong(block.revision()) < expected) {
                    throw new IllegalStateException(Messages.text("error.execution.executionMessageWriter.save.2"));
                }
                if (previous != null && previous.displayOrder() != block.displayOrder()) {
                    throw new IllegalStateException(Messages.text("error.execution.executionMessageWriter.save.3"));
                }
                blocks.put(block.id(), block);
                contentChanged = true;
                sequence = Long.parseLong(
                    events.append(run, "block.updated", Map.of("messageId", output.id(), "block", block),
                        clock.instant()).sequence());
            }
        }
        if (contentChanged) {
            var ordered = blocks.values().stream().sorted(Comparator.comparingInt(ContentBlock::displayOrder)).toList();
            String text = ordered.stream().filter(block -> block.type().equals("text") && block.parentBlockId() == null)
                .map(ContentBlock::text).collect(Collectors.joining("\n\n"));
            if (text.length() > 1_000_000) {
                throw new IllegalStateException(Messages.text("error.agent.frameEventPersistence.dispatch.1"));
            }
            messages.save(run.enterpriseId(), output.id(), text, ordered, "streaming", sequence, clock.instant());
        }
        jobs.savedEventBatch(lease, batchId, hash);
    }
}
