package com.stonewu.agenteam.model.execution.response;

import com.stonewu.agenteam.model.execution.entity.ModelSelection;

/**
 * 私有会话列表和详情共同使用的公开信息。
 */
public record ConversationView(String id, String revision, String createdAt, String updatedAt,
                               String title, String agentId, String agentName, String agentIcon, String agentColor,
                               String mode,
                               boolean favorite, String status, String activeRunId,
                               boolean canContinue, String unavailableReason, String approvalPolicy,
                               ModelSelection modelSelection, String projectId) {
}
