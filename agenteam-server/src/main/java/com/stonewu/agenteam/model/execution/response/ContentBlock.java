package com.stonewu.agenteam.model.execution.response;

import java.util.Map;
import java.util.Set;

/**
 * 消息中的公开内容块；编号、父块、显示顺序和修改版本在数据库保存。
 */
public record ContentBlock(String id, String type, String parentBlockId, int displayOrder,
                           String revision, String text, String status, String stepId,
                           String approvalId, Map<String, Object> file, Map<String, Object> citation, String label,
                           ToolBlockDetails tool,
                           String agentIcon, String agentColor) {
    public ContentBlock(String id, String type, String parentBlockId, int displayOrder, String revision, String text,
                        String status,
                        String stepId, String approvalId, Map<String, Object> file, Map<String, Object> citation,
                        String label, ToolBlockDetails tool) {
        this(id, type, parentBlockId, displayOrder, revision, text, status, stepId, approvalId, file, citation, label,
            tool, null, null);
    }

    public ContentBlock withAgentAppearance(String icon, String color) {
        return new ContentBlock(id, type, parentBlockId, displayOrder, revision, text, status, stepId, approvalId, file,
            citation, label, tool, icon, color);
    }

    public record ToolBlockDetails(String toolCallId, String name, String sourceKind, String input, String result,
                                   String callStatus, String resultStatus) {
    }

    public ContentBlock update(String value, String nextStatus) {
        ToolBlockDetails details = tool;
        if (details != null && Set.of("waiting_approval", "completed", "failed", "cancelled", "skipped")
            .contains(nextStatus)) {
            details = new ToolBlockDetails(details.toolCallId(), details.name(), details.sourceKind(), details.input(),
                details.result(),
                !nextStatus.equals("waiting_approval") && Set.of("pending", "running")
                    .contains(details.callStatus()) ? nextStatus : details.callStatus(),
                Set.of("pending", "running", "waiting_approval")
                    .contains(details.resultStatus()) ? nextStatus : details.resultStatus());
        }
        return new ContentBlock(id, type, parentBlockId, displayOrder,
            Long.toString(Math.addExact(Long.parseLong(revision), 1)), value, nextStatus,
            stepId, approvalId, file, citation, label, details, agentIcon, agentColor);
    }
}
