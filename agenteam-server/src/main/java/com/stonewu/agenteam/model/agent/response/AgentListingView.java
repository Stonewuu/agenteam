package com.stonewu.agenteam.model.agent.response;

/**
 * 员工广场的实际展示开关与雇佣策略。
 */
public record AgentListingView(boolean listed, String hirePolicy) {
}
