package com.stonewu.agenteam.model.collaboration.response;

import java.util.List;

/** 列表只展示实际任务状态，详细内容由本人单独读取。 */
public record CollaborationTaskSummary(String id, String shortCode, String title, String conversationId, String status,
        long revision, long inputRevision, String outputVisibility, String createdAt, String updatedAt,
        String deadlineAt, String finishedAt, String errorCode, Source source, List<Role> roles, String primaryConversationId) {
    public record Source(String platformCode, String kind, String name) {
    }

    public record Role(String id, String name, String conversationId) {
    }
}
