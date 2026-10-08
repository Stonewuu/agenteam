package com.stonewu.agenteam.model.agent.response;

/**
 * 每次请求只返回已建立的使用关系或需要审批的申请，不混合两种状态。
 */
public sealed interface AgentHireResult {
    record Hired(String kind, AgentHireView hire) implements AgentHireResult {
        public Hired(AgentHireView hire) {
            this("hired", hire);
        }
    }

    record ApprovalRequired(String kind, AgentHireApplicationView application) implements AgentHireResult {
        public ApprovalRequired(AgentHireApplicationView application) {
            this("approval_required", application);
        }
    }
}
