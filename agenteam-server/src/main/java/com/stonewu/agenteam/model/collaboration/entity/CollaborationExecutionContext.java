package com.stonewu.agenteam.model.collaboration.entity;

import com.stonewu.agenteam.model.auth.entity.AuthContext;

/** 由数据库执行关联恢复，不接受模型提供企业、用户或工作编号。 */
public record CollaborationExecutionContext(AuthContext actor, CollaborationTaskRow task,
        CollaborationWorkItemRow work, CollaborationParticipantRow participant, CollaborationRunLinkRow link) {
}
