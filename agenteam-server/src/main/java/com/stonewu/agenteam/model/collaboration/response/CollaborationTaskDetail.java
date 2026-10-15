package com.stonewu.agenteam.model.collaboration.response;

import java.util.List;
import java.util.Map;

/** 任务详情不包含加密载荷、平台票据、模型推理和私有系统提示词。 */
public record CollaborationTaskDetail(CollaborationTaskSummary task, Map<String, Object> requirements, String ownerWorkId,
        String ownerRoleId, List<Role> roles, List<Work> works, List<Map<String, Object>> artifacts,
        List<Question> questions, List<Delivery> deliveries, boolean fullOutputAllowed) {
    public record Role(String id, String botId, String name, String description, String agentId, String versionId,
            boolean explicitlyRequested, String availability, String conversationId) {
    }
    public record Work(String id, String roleId, String purpose, String status, boolean required, long inputRevision,
            int runRound, String activeRunId, String errorCode, String resultArtifactId, String replacementWorkId) {
    }
    public record Question(String id, String workId, long inputRevision, String text, List<String> options, String createdAt) {
    }
    public record Delivery(String id, String botId, String kind, String status, String errorCode, String createdAt) {
    }
}
