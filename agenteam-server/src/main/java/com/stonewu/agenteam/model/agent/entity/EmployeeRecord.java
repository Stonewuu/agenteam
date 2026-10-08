package com.stonewu.agenteam.model.agent.entity;

import java.time.Instant;
import java.util.List;

/**
 * 员工介绍只读取发布公开字段与本人的使用关系，不携带系统指令和凭据。
 */
public record EmployeeRecord(String id, String name, String description, String businessRole, String icon, String color,
                             List<String> examples, List<String> skillVersions, String welcomeMessage,
                             List<String> suggestedQuestions,
                             String resourceStatus, boolean ownerActive,
                             String versionStatus, boolean modelAvailable, String hireId, String hireRevision,
                             String applicationId, String applicationRevision, String hireStatus,
                             boolean listed, boolean requiresApproval, boolean attachmentsEnabled,
                             Instant positionTime) {
}
