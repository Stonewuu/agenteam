package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.tool.ToolPayloadPreview;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.MessageView;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.file.Utf8Text;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 保留中断前已保存的文字和工具结果；工具记录作为历史文字传入，不恢复待执行请求。
 */
public final class AgentHistoryMessageMapper {

    private AgentHistoryMessageMapper() {
    }

    public static Msg map(MessageView message, String sourceContext) {
        return map(message, sourceContext, Map.of());
    }

    public static Msg map(MessageView message, String sourceContext, Map<String, ToolCallRecord> callsByStep) {
        String content = message.content() + (message.role().equals("user") ? sourceContext : "");
        if (message.role().equals("assistant")) {
            var fragments = new ArrayList<String>();
            var ordered = message.blocks().stream().sorted(Comparator.comparingInt(ContentBlock::displayOrder))
                .toList();
            String blockText = ordered.stream()
                .filter(block -> block.type().equals("text") && block.parentBlockId() == null).map(ContentBlock::text)
                .collect(Collectors.joining("\n\n"));
            boolean textMatches = blockText.equals(content);
            if (!textMatches && !content.isBlank()) {
                fragments.add(content);
            }
            Map<String, ContentBlock> blocks = ordered.stream()
                .collect(Collectors.toMap(ContentBlock::id, Function.identity()));
            for (var block : ordered) {
                if (block.type().equals("text") && !block.text().isBlank()) {
                    if (block.parentBlockId() == null) {
                        if (textMatches) {
                            fragments.add(block.text());
                        }
                    } else {
                        var parent = blocks.get(block.parentBlockId());
                        fragments.add(
                            (parent == null || parent.label() == null ? "子任务内容" : parent.label()) + "：\n" + block.text());
                    }
                } else if (block.tool() != null) {
                    fragments.add(toolHistory(block, block.stepId() == null ? null : callsByStep.get(block.stepId()),
                        message.status()));
                }
            }
            if (message.status().equals("cancelled")) {
                fragments.add("（本轮回复已停止。）");
            } else if (message.status().equals("failed")) {
                fragments.add("（本轮回复未完成。）");
            }
            content = String.join("\n\n", fragments);
        }
        return Msg.builder().id(message.id()).role(message.role().equals("user") ? MsgRole.USER : MsgRole.ASSISTANT)
            .content(List.of(TextBlock.builder().text(content).build())).build();
    }

    private static String toolHistory(ContentBlock block, ToolCallRecord call, String messageStatus) {
        var tool = block.tool();
        String label = block.label() == null || block.label().isBlank() ? tool.name() : block.label();
        String state = call == null ? tool.resultStatus() : call.status();
        var parts = new ArrayList<String>();
        parts.add("历史工具记录：" + label + "\n状态：" + toolStatus(state, messageStatus));
        String input = call == null || call.requestRedacted() == null ? ToolPayloadPreview.text(
            tool.input()) : ToolPayloadPreview.value(call.requestRedacted());
        if (input != null && !input.isBlank()) {
            parts.add("调用参数：\n" + input);
        }
        String result = historyResult(call, tool.result());
        if (result != null && !result.isBlank()) {
            parts.add("已保存的返回内容：\n" + result);
        }
        if (call != null && call.errorSummary() != null && !call.errorSummary().isBlank()) {
            parts.add("调用说明：" + call.errorSummary());
        }
        return String.join("\n", parts);
    }

    private static String toolStatus(String state, String messageStatus) {
        return switch (state == null ? "" : state) {
            case "succeeded", "completed" -> "已完成";
            case "cancelled" -> "已停止";
            case "failed" -> "失败";
            case "unknown" -> "结果尚未确认";
            case "skipped" -> "已跳过";
            default -> messageStatus.equals("cancelled") ? "已停止" : "未完成";
        };
    }

    private static String historyResult(ToolCallRecord call, String preview) {
        if (call == null || call.resultRedacted() == null) {
            return ToolPayloadPreview.text(preview);
        }
        var result = call.resultRedacted();
        String content = result.toString();
        if (Utf8Text.size(content) <= ToolPayloadPreview.MAX_BYTES) {
            return content;
        }
        ObjectNode reference;
        if (result instanceof ObjectNode object && result.path("path").asText()
            .startsWith("tool-results/") && result.has("rawPath")) {
            reference = object.deepCopy();
        } else {
            reference = ToolResultContent.reference(call.id(), null, result, Utf8Text.size(result.toPrettyString()),
                ToolPayloadPreview.MAX_BYTES / 2);
        }
        return ToolResultContent.fitReference(reference, ToolPayloadPreview.MAX_BYTES).toString();
    }
}
