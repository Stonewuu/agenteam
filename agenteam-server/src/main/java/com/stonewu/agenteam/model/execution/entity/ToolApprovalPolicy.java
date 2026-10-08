package com.stonewu.agenteam.model.execution.entity;

/**
 * 会话工具审批策略；只决定是否逐次确认，不授予资源访问权限。
 */
public enum ToolApprovalPolicy {
    DEFAULT("default"), AUTO_APPROVE("auto_approve"), FULL_ACCESS("full_access");

    private final String value;

    ToolApprovalPolicy(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean requiresApproval(String operationClass) {
        if ("read".equals(operationClass) || this == FULL_ACCESS) {
            return false;
        }
        return this != AUTO_APPROVE || !"write".equals(operationClass);
    }

    /**
     * 缺少设置的历史任务及无法识别的值都使用默认策略。
     */
    public static ToolApprovalPolicy from(String value) {
        for (var policy : values()) {
            if (policy.value.equals(value)) {
                return policy;
            }
        }
        return DEFAULT;
    }

    public static ToolApprovalPolicy forRun(RunRecord run) {
        return "interactive".equals(run.mode()) ? from(run.executionConfig().path("approvalPolicy").asText()) : DEFAULT;
    }
}
