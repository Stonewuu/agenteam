package com.stonewu.agenteam.service.execution;

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
 * 同一事务更新完整消息块与连续事件，失去租约的旧进程无法保存晚到内容。
 */
@Service
public class ExecutionMessageWriter {
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final ExecutionMessageMapper messages;
    private final ExecutionEventMapper events;
    private final RunStepMapper steps;
    private final RunLifecycleService lifecycle;
    private final Clock clock;
    private final RunJobMapper jobs;
    private final ResourceJson json;

    public ExecutionMessageWriter(ConversationMapper conversations, RunMapper runs, ExecutionMessageMapper messages,
                                  ExecutionEventMapper events, RunStepMapper steps, RunLifecycleService lifecycle,
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
        var conversation = conversations.find(lease.enterpriseId(), lease.userId(), found.conversationId(), true)
            .orElseThrow();
        var run = runs.find(lease.enterpriseId(), lease.runId(), true).orElseThrow();
        lifecycle.requireLease(run, lease);
        String hash = json.hash(json.tree(changes));
        var applied = jobs.lastEventBatch(lease);
        if (batchId.equals(applied.id())) {
            if (!hash.equals(applied.hash())) {
                throw new IllegalStateException("同一事件批次不能用于不同内容");
            }
            return;
        }
        var output = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow();
        Map<String, ContentBlock> blocks = new LinkedHashMap<>();
        output.blocks().forEach(block -> blocks.put(block.id(), block));
        long sequence = run.lastSequence();
        for (var change : changes) {
            if (change.step() != null) {
                steps.save(run, change.step(), change.input(), change.result(), clock.instant());
                sequence = Long.parseLong(
                    events.append(run, "step.updated", change.step(), clock.instant()).sequence());
            } else {
                var block = change.block();
                var previous = blocks.get(block.id());
                if (conversation.liveEvents() && block.equals(previous)) {
                    continue;
                }
                long expected = previous == null ? 1 : Math.addExact(Long.parseLong(previous.revision()), 1);
                if (conversation.liveEvents()) {
                    if (change.delta() != null || change.baseRevision() == null
                        || !change.baseRevision().equals(previous == null ? "0" : previous.revision())
                        || Long.parseLong(block.revision()) < expected) {
                        throw new IllegalStateException("完整内容块与上次已保存版本不一致，不能覆盖历史内容");
                    }
                } else if (Long.parseLong(block.revision()) != expected) {
                    throw new IllegalStateException("待保存消息块与数据库版本不一致，不能继续追加");
                }
                if (previous != null && previous.displayOrder() != block.displayOrder()) {
                    throw new IllegalStateException("待保存消息块与数据库版本不一致，不能继续追加");
                }
                if (change.delta() != null && (previous == null || !previous.revision()
                    .equals(change.baseRevision()))) {
                    throw new IllegalStateException("待保存正文与数据库版本不一致，不能继续追加");
                }
                blocks.put(block.id(), block);
                Object payload = change.delta() == null ? Map.of("messageId", output.id(), "block", block)
                    : Map.of("messageId", output.id(), "blockId", block.id(), "baseRevision", change.baseRevision(),
                    "revision", block.revision(), "delta", change.delta());
                sequence = Long.parseLong(
                    events.append(run, change.delta() == null ? "block.updated" : "message.delta", payload,
                        clock.instant()).sequence());
            }
        }
        var ordered = blocks.values().stream().sorted(Comparator.comparingInt(ContentBlock::displayOrder)).toList();
        String text = ordered.stream().filter(block -> block.type().equals("text") && block.parentBlockId() == null)
            .map(ContentBlock::text).collect(Collectors.joining("\n\n"));
        if (text.length() > 1_000_000) {
            throw new IllegalStateException("消息总长度超过允许范围");
        }
        messages.save(run.enterpriseId(), output.id(), text, ordered, "streaming", sequence, clock.instant());
        jobs.savedEventBatch(lease, batchId, hash);
    }
}
