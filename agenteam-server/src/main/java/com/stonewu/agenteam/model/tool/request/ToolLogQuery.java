package com.stonewu.agenteam.model.tool.request;

/**
 * 调用日志的明确筛选，不接受字段名称或查询表达式。
 */
public record ToolLogQuery(String from, String to, String actorUserId, String status, String source, String query) {
}
