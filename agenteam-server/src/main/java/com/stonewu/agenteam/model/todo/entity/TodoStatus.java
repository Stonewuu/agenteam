package com.stonewu.agenteam.model.todo.entity;

/**
 * 状态变化只表达用户明确的开始、完成、取消和重开，不根据日期自动结束。
 */
public enum TodoStatus {
    PENDING("pending", "待处理"), IN_PROGRESS("in_progress", "进行中"), COMPLETED("completed", "已完成"), CANCELLED(
        "cancelled", "已取消");
    private final String code;
    private final String label;

    TodoStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public boolean open() {
        return this == PENDING || this == IN_PROGRESS;
    }

    public boolean canChangeTo(TodoStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case PENDING -> target == IN_PROGRESS || target == COMPLETED || target == CANCELLED;
            case IN_PROGRESS -> target == COMPLETED || target == CANCELLED;
            case COMPLETED, CANCELLED -> target == PENDING;
        };
    }

    public static TodoStatus from(String code) {
        for (var value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("待办状态不正确");
    }
}
