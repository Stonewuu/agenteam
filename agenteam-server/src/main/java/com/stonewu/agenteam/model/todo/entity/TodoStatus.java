package com.stonewu.agenteam.model.todo.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 状态变化只表达用户明确的开始、完成、取消和重开，不根据日期自动结束。
 */
public enum TodoStatus {
    PENDING("pending", Messages.source("text.todo.todoStatus.PENDING.1")), IN_PROGRESS("in_progress", Messages.source("text.todo.todoStatus.IN_PROGRESS.1")), COMPLETED("completed", Messages.source("text.agent.agentHistoryMessageMapper.toolStatus.1")), CANCELLED(
        "cancelled", Messages.source("text.todo.todoStatus.CANCELLED.1"));
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
        throw new IllegalArgumentException(Messages.text("error.todo.todoStatus.from.1"));
    }
}
