package com.stonewu.agenteam.model.execution.response;

import java.util.List;

/**
 * 数据库历史与当前累计内容，以及与返回正文相符的后续事件读取位置。
 */
public record ConversationSnapshotView(ConversationView conversation, List<MessageView> messages,
                                       RunView activeRun, String lastSequence, boolean hasOlderMessages,
                                       String nextBeforeMessageId, boolean attachmentsEnabled, int protocolVersion,
                                       StreamCursor streamCursor, List<LiveRunStepView> liveSteps,
                                       List<RunApprovalView> approvals) {
    public ConversationSnapshotView(ConversationView conversation, List<MessageView> messages, RunView activeRun,
                                    String lastSequence, boolean hasOlderMessages, String nextBeforeMessageId,
                                    boolean attachmentsEnabled) {
        this(conversation, messages, activeRun, lastSequence, hasOlderMessages, nextBeforeMessageId,
            attachmentsEnabled, 1, null, List.of(), List.of());
    }
}
