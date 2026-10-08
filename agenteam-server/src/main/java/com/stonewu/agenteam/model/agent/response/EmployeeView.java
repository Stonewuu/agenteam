package com.stonewu.agenteam.model.agent.response;

import java.util.List;

public record EmployeeView(String agentId, String name, String description, String businessRole, String icon,
                           String color,
                           List<String> examples, String welcomeMessage, List<String> suggestedQuestions,
                           List<String> tags,
                           String hireId, String hireRevision, String applicationId, String applicationRevision,
                           String hireStatus, boolean requiresApproval,
                           boolean canHire, boolean canResume, boolean canRun, String unavailableReason,
                           List<Skill> skills, boolean attachmentsEnabled) {
    public record Skill(String id, String name, String description) {
    }
}
