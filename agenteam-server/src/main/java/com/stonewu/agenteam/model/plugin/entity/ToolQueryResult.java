package com.stonewu.agenteam.model.plugin.entity;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 查询只能报告可核实的结果；查不到记录不能自动解释为尚未执行。
 */
public record ToolQueryResult(Status status, JsonNode result) {
    public enum Status {COMPLETED, NOT_EXECUTED, UNKNOWN}

    public ToolQueryResult {
        if (status == null || (status == Status.COMPLETED && result == null)) {
            throw new IllegalArgumentException(Messages.text("error.plugin.toolQueryResult.init.1"));
        }
    }

    public static ToolQueryResult unknown() {
        return new ToolQueryResult(Status.UNKNOWN, null);
    }
}
