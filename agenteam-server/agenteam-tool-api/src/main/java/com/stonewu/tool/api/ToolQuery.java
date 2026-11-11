package com.stonewu.tool.api;

import java.util.Map;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * 查询只报告已经核实的结果，未找到记录不能直接判定未执行。
 */
public record ToolQuery(Status status, Map<String, Object> result) {

    public enum Status {

        COMPLETED, NOT_EXECUTED, UNKNOWN
    }

    public ToolQuery {
        if (status == null || status == Status.COMPLETED && result == null) {
            throw new IllegalArgumentException(ResourceBundle.getBundle("com.stonewu.tool.api.messages",
                Locale.getDefault()).getString("query.incomplete"));
        }
    }

    public static ToolQuery unknown() {
        return new ToolQuery(Status.UNKNOWN, null);
    }
}
