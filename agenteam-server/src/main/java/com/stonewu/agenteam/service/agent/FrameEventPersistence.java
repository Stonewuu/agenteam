package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.agent.AgentPublicEventMapper;
import com.stonewu.agenteam.model.agent.entity.AgentEventRecord;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.execution.ExecutionMessageWriter;
import com.stonewu.agenteam.service.execution.LiveExecutionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 流式片段只用于实时发送，每轮模型输出结束时统一保存完整内容和调用参数。
 */
public final class FrameEventPersistence implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FrameEventPersistence.class);
    private final RunRecord run;
    private final JobLease lease;
    private final AgentPublicEventMapper mapper;
    private final ExecutionMessageWriter writer;
    private final LiveExecutionSession live;
    private final AgentEventChunkAggregator aggregator = new AgentEventChunkAggregator();
    private final Map<String, ExecutionChange> pendingBlocks = new LinkedHashMap<>();
    private final Map<String, ExecutionChange> pendingSteps = new LinkedHashMap<>();
    private final Map<String, Stream> streamOwners = new HashMap<>();
    private final Map<String, String> textScopes = new HashMap<>();
    private final Disposable timer;
    private RuntimeException error;
    private long sequence;
    private boolean closed;

    public FrameEventPersistence(RunRecord run, JobLease lease, AgentPublicEventMapper mapper,
                                 ExecutionMessageWriter writer, Scheduler scheduler, LiveExecutionSession live) {
        this.run = run;
        this.lease = lease;
        this.mapper = mapper;
        this.writer = writer;
        this.live = Objects.requireNonNull(live);
        timer = scheduler.schedulePeriodically(() -> {
            try {
                flushDelivery();
            } catch (RuntimeException failed) {
                LOG.error(Messages.log("log.agent.frameEventPersistence.init.1"), run.id(), failed);
                failure().tryEmitError(failed);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    public Sinks.Empty<Void> failure() {
        return live.failure();
    }

    /**
     * 在同一消息锁内读取已接收内容，不生成额外事件或数据库保存。
     */
    public synchronized <T> T read(Function<AgentPublicEventMapper, T> action) {
        requireOpen();
        return action.apply(mapper);
    }

    public void update(Function<AgentPublicEventMapper, List<ExecutionChange>> action) {
        synchronized (this) {
            requireOpen();
            process(aggregator.flush(run.id()));
            dispatch(action.apply(mapper), false, (batch, values) -> writer.save(lease, batch, values), false);
        }
        live.drain();
    }

    public void update(Function<AgentPublicEventMapper, List<ExecutionChange>> action,
                       BiConsumer<String, List<ExecutionChange>> commit) {
        synchronized (this) {
            requireOpen();
            process(aggregator.flush(run.id()));
            dispatch(action.apply(mapper), false, commit, true);
        }
        live.drain();
    }

    public Stream stream() {
        return new Stream();
    }

    /**
     * 节点结束只结束自身文本块，其他并行节点继续输出。
     */
    public final class Stream implements AutoCloseable {
        private final Set<String> blocks = new HashSet<>();
        private boolean ended;

        public void accept(AgentStreamEvent event) {
            FrameEventPersistence.this.accept(event, this);
        }

        public void finish(String status) {
            synchronized (FrameEventPersistence.this) {
                if (ended) {
                    return;
                }
                process(aggregator.flush(run.id()));
                dispatch(mapper.presentation().finishTextBlocks(blocks, status), true,
                    (batch, values) -> writer.save(lease, batch, values), false);
                persistPending();
                ended = true;
            }
            live.drain();
        }

        @Override
        public void close() {
            finish("failed");
        }
    }

    public void accept(AgentStreamEvent event) {
        accept(event, null);
    }

    private void accept(AgentStreamEvent event, Stream owner) {
        boolean awaitSave;
        synchronized (this) {
            if (owner != null && owner.ended) {
                return;
            }
            requireOpen();
            if (owner != null && textEvent(event.type())) {
                streamOwners.put(eventKey(event), owner);
            }
            awaitSave = process(aggregator.acceptForPersistence(event.withExecution(run.conversationId(), run.id(), ++sequence)));
        }
        if (awaitSave) {
            live.drain();
        }
    }

    /**
     * 显式读取边界等待已经结束块保存，定时器不会调用这个方法。
     */
    public void flush() {
        flushDelivery();
        synchronized (this) {
            persistPending();
        }
        live.drain();
    }

    private synchronized void flushDelivery() {
        if (!closed) {
            requireOpen();
            process(aggregator.flush(run.id()));
        }
    }

    @Override
    public void close() {
        try {
            synchronized (this) {
                if (closed) {
                    return;
                }
                timer.dispose();
                try {
                    process(aggregator.flush(run.id()));
                    dispatch(mapper.presentation().finishTextBlocks(Set.copyOf(textScopes.keySet()), "running"),
                        true, (batch, values) -> writer.save(lease, batch, values), false);
                    persistPending();
                } finally {
                    closed = true;
                    aggregator.release(run.id());
                    streamOwners.clear();
                }
            }
        } finally {
            live.close();
        }
    }

    private boolean process(List<AgentEventRecord> records) {
        if (records.isEmpty()) {
            return false;
        }
        boolean awaitSave = false;
        try {
            for (var record : records) {
                boolean modelToolCall = mapper.modelToolCall(record);
                boolean boundary = Set.of("TOOL_RESULT_START", "TOOL_RESULT_END", "MODEL_CALL_END", "AGENT_END")
                    .contains(record.eventType()) || record.eventType().startsWith("TOOL_CALL_") && !modelToolCall;
                awaitSave |= boundary;
                if (boundary) {
                    String scope = scope(record.source(), record.metadata(), record.replyId());
                    String prefix = scope(record.source(), record.metadata(), null);
                    var selected = textScopes.entrySet().stream()
                        .filter(
                            entry -> record.replyId() == null ? entry.getValue().startsWith(prefix) : entry.getValue()
                                .equals(scope))
                        .map(Map.Entry::getKey).collect(Collectors.toSet());
                    dispatch(mapper.presentation().finishTextBlocks(selected, "completed"), true,
                        (batch, values) -> writer.save(lease, batch, values), false);
                }
                var changes = mapper.accept(record);
                if (textEvent(record.eventType())) {
                    var owner = streamOwners.get(eventKey(record));
                    for (var change : changes) {
                        if (change.block() != null) {
                            textScopes.put(change.block().id(),
                                scope(record.source(), record.metadata(), record.replyId()));
                            if (owner != null) {
                                owner.blocks.add(change.block().id());
                            }
                        }
                    }
                }
                dispatch(changes, false, (batch, values) -> writer.save(lease, batch, values), false,
                    record.eventType(), modelToolCall);
                if (boundary) {
                    persistPending();
                }
            }
            return awaitSave;
        } catch (RuntimeException failed) {
            error = failed;
            LOG.error(Messages.log("log.agent.frameEventPersistence.process.1"), run.id(), failed);
            throw failed;
        }
    }

    private void dispatch(List<ExecutionChange> changes, boolean partial,
                          BiConsumer<String, List<ExecutionChange>> commit, boolean saveEmpty) {
        dispatch(changes, partial, commit, saveEmpty, "", false);
    }

    private void dispatch(List<ExecutionChange> changes, boolean partial,
                          BiConsumer<String, List<ExecutionChange>> commit, boolean saveEmpty, String eventType,
                          boolean modelToolCall) {
        var content = List.copyOf(mapper.presentation().blocks().values());
        long textLength = content.stream().filter(block -> block.type().equals("text") && block.parentBlockId() == null)
            .mapToLong(block -> block.text().length() + 2L).sum();
        if (textLength > 1_000_002) {
            throw new IllegalStateException(Messages.text("error.agent.frameEventPersistence.dispatch.1"));
        }
        live.publish(changes.stream().filter(change -> change.block() == null
            || !Set.of("completed", "failed", "cancelled", "skipped").contains(change.block().status())).toList(), content);
        if (modelToolCall) {
            // 调用参数尚在生成时不保存消息；完整参数随本轮模型结果一起提交。
            if (eventType.equals("TOOL_CALL_END")) {
                for (var change : changes) {
                    if (change.block() != null) {
                        pendingBlocks.put(change.block().id(), change);
                        var step = mapper.presentation().steps().get(change.block().stepId());
                        if (step != null) {
                            pendingSteps.put(step.id(), ExecutionChange.step(step));
                        }
                    }
                }
            }
            return;
        }
        boolean deltaEvent = eventType.endsWith("_DELTA");
        var completed = new LinkedHashMap<String, ExecutionChange>();
        for (var change : changes) {
            if (change.step() != null) {
                completed.put("step:" + change.step().id(), change);
                continue;
            }
            var block = change.block();
            boolean text = block.type().equals("text") || block.type().equals("thinking");
            boolean ended = Set.of("completed", "failed", "cancelled", "skipped").contains(block.status());
            if (change.delta() == null && (partial || (text ? ended : !deltaEvent))) {
                if (text) {
                    pendingBlocks.put(block.id(), change);
                } else {
                    completed.put("block:" + block.id(), change);
                }
            }
        }
        if (!completed.isEmpty() || saveEmpty) {
            var saved = new ArrayList<>(pendingSteps.values());
            saved.addAll(pendingBlocks.values());
            saved.addAll(completed.values());
            live.persist(saved, commit, saveEmpty);
            pendingBlocks.clear();
            pendingSteps.clear();
        }
    }

    private void persistPending() {
        if (!pendingBlocks.isEmpty() || !pendingSteps.isEmpty()) {
            var saved = new ArrayList<>(pendingSteps.values());
            saved.addAll(pendingBlocks.values());
            live.persist(saved, (batch, values) -> writer.save(lease, batch, values), false);
            pendingBlocks.clear();
            pendingSteps.clear();
        }
    }

    private static boolean textEvent(String type) {
        return type != null && (type.startsWith("TEXT_BLOCK_") || type.startsWith("THINKING_BLOCK_"));
    }

    private String eventKey(AgentStreamEvent event) {
        return scope(event.source(), event.metadata(), event.replyId()) + "|" + event.blockId();
    }

    private String eventKey(AgentEventRecord event) {
        return scope(event.source(), event.metadata(), event.replyId()) + "|" + event.blockId();
    }

    private String scope(String source, Map<String, Object> metadata, String reply) {
        return Objects.toString(source, "") + "|" + Objects.toString(
            metadata.get(ExecutionGuardMiddleware.OWNER_SESSION_METADATA), "")
            + "|" + Objects.toString(metadata.get(ExecutionGuardMiddleware.SESSION_METADATA),
            "") + "|" + Objects.toString(reply, "");
    }

    private void requireOpen() {
        if (error != null) {
            throw error;
        }
        if (closed) {
            throw new IllegalStateException(Messages.text("error.agent.frameEventPersistence.requireOpen.1"));
        }
        live.requireOpen();
    }
}
