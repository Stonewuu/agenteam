package com.stonewu.agenteam.model.execution.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 数据库历史与当前累计内容，以及与返回正文相符的后续事件读取位置。
 */
public record ConversationSnapshotView(ConversationView conversation, List<MessageView> messages,
                                       RunView activeRun, String lastSequence, boolean hasOlderMessages,
                                       String nextBeforeMessageId, boolean attachmentsEnabled, int protocolVersion,
                                       StreamCursor streamCursor, List<LiveRunStepView> liveSteps,
                                       List<RunApprovalView> approvals, List<QueuedMessageView> queuedMessages,
                                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, RunStepHistoryView> stepHistory) {
    public ConversationSnapshotView(ConversationView conversation, List<MessageView> messages, RunView activeRun,
                                    String lastSequence, boolean hasOlderMessages, String nextBeforeMessageId,
                                    boolean attachmentsEnabled, int protocolVersion, StreamCursor streamCursor,
                                    List<LiveRunStepView> liveSteps, List<RunApprovalView> approvals,
                                    List<QueuedMessageView> queuedMessages) {
        this(conversation, messages, activeRun, lastSequence, hasOlderMessages, nextBeforeMessageId, attachmentsEnabled,
            protocolVersion, streamCursor, liveSteps, approvals, queuedMessages, null);
    }

    public ConversationSnapshotView(ConversationView conversation, List<MessageView> messages, RunView activeRun,
                                    String lastSequence, boolean hasOlderMessages, String nextBeforeMessageId,
                                    boolean attachmentsEnabled, int protocolVersion, StreamCursor streamCursor,
                                    List<LiveRunStepView> liveSteps, List<RunApprovalView> approvals) {
        this(conversation, messages, activeRun, lastSequence, hasOlderMessages, nextBeforeMessageId, attachmentsEnabled,
            protocolVersion, streamCursor, liveSteps, approvals, List.of(), null);
    }

    public ConversationSnapshotView(ConversationView conversation, List<MessageView> messages, RunView activeRun,
                                    String lastSequence, boolean hasOlderMessages, String nextBeforeMessageId,
                                    boolean attachmentsEnabled) {
        this(conversation, messages, activeRun, lastSequence, hasOlderMessages, nextBeforeMessageId,
            attachmentsEnabled, 1, null, List.of(), List.of(), List.of(), null);
    }

    public ConversationSnapshotView withStepHistory(Map<String, RunStepHistoryView> value) {
        return new ConversationSnapshotView(conversation, messages, activeRun, lastSequence, hasOlderMessages,
            nextBeforeMessageId, attachmentsEnabled, protocolVersion, streamCursor, liveSteps, approvals,
            queuedMessages, value);
    }
}
