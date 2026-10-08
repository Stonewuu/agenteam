package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.execution.entity.LiveConversationState;
import com.stonewu.agenteam.model.execution.response.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 按消息和块标识组合完整内容，不能把缓存全文追加到数据库已有前缀。
 */
@Component
public class LiveSnapshotMapper {
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled");
    private final ObjectMapper json;

    public LiveSnapshotMapper(ObjectMapper json) {
        this.json = json;
    }

    public ConversationSnapshotView merge(ConversationSnapshotView base, LiveConversationState live,
                                          Map<String, String> attemptMessages) {
        if (!live.covers(Long.parseLong(base.lastSequence()))) {
            throw new IllegalArgumentException("数据库快照与 Redis 保留内容的版本不对应");
        }
        Map<String, MessageView> messages = new LinkedHashMap<>();
        base.messages().forEach(message -> messages.put(message.id(), message));
        Map<String, RunView> runs = new LinkedHashMap<>();
        if (base.activeRun() != null) {
            runs.put(base.activeRun().id(), base.activeRun());
        }
        for (var entry : live.objects().entrySet()) {
            if (entry.getKey().startsWith("message:")) {
                var message = json.convertValue(entry.getValue(), MessageView.class);
                messages.putIfAbsent(message.id(), message);
            } else if (entry.getKey().startsWith("run:")) {
                var run = json.convertValue(entry.getValue(), RunView.class);
                runs.put(run.id(), run);
            }
        }
        for (var entry : live.objects().entrySet()) {
            var value = entry.getValue();
            if (entry.getKey().startsWith("block:")) {
                var message = messages.get(value.path("messageId").asText());
                if (message != null) {
                    var block = json.convertValue(value.path("block"), ContentBlock.class);
                    messages.put(message.id(), mergeBlock(message, block));
                }
            } else if (entry.getKey().startsWith("attempt:")) {
                var message = messages.get(value.path("outputMessageId").asText());
                if (message != null) {
                    messages.put(message.id(), message(message, message.blocks(), value.path("status").asText()));
                }
            }
        }
        for (var run : runs.values()) {
            var message = messages.get(run.outputMessageId());
            if (message != null) {
                String status = TERMINAL.contains(run.status()) ? run.status() : run.status()
                    .equals("queued") ? "pending" : "streaming";
                messages.put(message.id(), message(message, message.blocks(), status));
            }
        }
        RunView active = live.activeRunId() == null ? null : runs.get(live.activeRunId());
        if (live.activeRunId() != null && active == null) {
            throw new IllegalStateException("累计内容中缺少当前执行状态");
        }
        var ordered = new ArrayList<>(messages.values().stream().sorted(Comparator.comparing(MessageView::createdAt)
            .thenComparing(message -> message.role().equals("user") ? 0 : 1).thenComparing(MessageView::id)).toList());
        boolean older = base.hasOlderMessages() || ordered.size() > 100;
        while (ordered.size() > 100) {
            ordered.removeFirst();
        }
        var steps = new ArrayList<LiveRunStepView>();
        var approvals = new ArrayList<RunApprovalView>();
        for (var entry : live.objects().entrySet()) {
            if (entry.getKey().startsWith("step:")) {
                var value = entry.getValue();
                String run = value.path("runId").asText();
                var step = json.convertValue(value.path("step"), RunStepView.class);
                var owner = ordered.stream()
                    .filter(message -> run.equals(message.runId()) && message.role().equals("assistant"))
                    .filter(message -> message.id().equals(attemptMessages.get(step.attemptId()))).findFirst()
                    .orElse(null);
                if (owner != null) {
                    steps.add(new LiveRunStepView(run, owner.id(), live.cursor().sequence(), step));
                }
            } else if (entry.getKey().startsWith("approval:")) {
                approvals.add(json.convertValue(entry.getValue(), RunApprovalView.class));
            }
        }
        return new ConversationSnapshotView(conversation(base.conversation(), active), ordered, active,
            Long.toString(live.appliedDatabaseVersion()), older,
            older && !ordered.isEmpty() ? ordered.getFirst().id() : null,
            base.attachmentsEnabled(), 2, live.cursor(), steps, approvals);
    }

    public ConversationSnapshotView unavailable(ConversationSnapshotView base) {
        return new ConversationSnapshotView(base.conversation(), base.messages(), base.activeRun(), base.lastSequence(),
            base.hasOlderMessages(), base.nextBeforeMessageId(), base.attachmentsEnabled(), 2, null, List.of(),
            List.of());
    }

    private MessageView mergeBlock(MessageView message, ContentBlock block) {
        var blocks = new LinkedHashMap<String, ContentBlock>();
        message.blocks().forEach(value -> blocks.put(value.id(), value));
        var previous = blocks.get(block.id());
        if (previous == null || Long.parseLong(block.revision()) > Long.parseLong(previous.revision())) {
            blocks.put(block.id(), block);
        }
        var ordered = blocks.values().stream().sorted(Comparator.comparingInt(ContentBlock::displayOrder)).toList();
        return message(message, ordered, message.status());
    }

    private MessageView message(MessageView original, List<ContentBlock> blocks, String status) {
        String content = original.role().equals("assistant") ? blocks.stream()
            .filter(block -> block.type().equals("text") && block.parentBlockId() == null)
            .map(ContentBlock::text).collect(Collectors.joining("\n\n")) : original.content();
        return new MessageView(original.id(), original.runId(), original.attemptNo(), original.role(), content, status,
            blocks, original.attachments(), original.feedback(), original.createdAt(), original.updatedAt());
    }

    private ConversationView conversation(ConversationView value, RunView active) {
        return new ConversationView(value.id(), value.revision(), value.createdAt(), value.updatedAt(), value.title(),
            value.agentId(),
            value.agentName(), value.agentIcon(), value.agentColor(), value.mode(), value.favorite(), value.status(),
            active == null ? null : active.id(), active == null && value.canContinue(), value.unavailableReason(),
            value.approvalPolicy(), value.modelSelection(), value.projectId());
    }
}
