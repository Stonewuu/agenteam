package com.stonewu.agenteam.model.resource.response;

import java.util.List;

/**
 * 只表示指定时间的一次真实检查结果。
 */
public record ConnectionCheckView(String checkedAt, boolean success, String summary, long durationMs,
                                  List<ToolChange> toolChanges) {
    public record ToolChange(String name, String change) {
    }
}
