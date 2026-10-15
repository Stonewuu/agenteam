package com.stonewu.agenteam.mapper.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskSummary;

import java.time.Instant;
import java.util.List;

/** 列表与详情共用任务标题和基础字段转换。 */
public final class CollaborationTaskViewMapper {
    private CollaborationTaskViewMapper() {
    }

    public static CollaborationTaskSummary summary(CollaborationTaskRow task, JsonNode requirements) {
        return summary(task, requirements, null, List.of(), null);
    }

    public static CollaborationTaskSummary summary(CollaborationTaskRow task, JsonNode requirements,
            CollaborationTaskSummary.Source source, List<CollaborationTaskSummary.Role> roles, String conversationId) {
        String text = requirements.path("text").asText();
        int count = Math.min(100, text.codePointCount(0, text.length()));
        String title = text.substring(0, text.offsetByCodePoints(0, count));
        return new CollaborationTaskSummary(task.getId(), task.getShortCode(), title, task.getConversationId(), task.getStatus(),
            task.getRevision(), task.getInputRevision(), task.getOutputVisibility(), time(task.getCreatedAt()), time(task.getUpdatedAt()),
            time(task.getDeadlineAt()), time(task.getFinishedAt()), task.getErrorCode(), source, roles, conversationId);
    }

    private static String time(Instant value) {
        return value == null ? null : value.toString();
    }
}
