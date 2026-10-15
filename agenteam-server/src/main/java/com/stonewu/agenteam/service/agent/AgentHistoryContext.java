package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentHistoryMessageMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 完整保留已有上下文，分批补回缺失的较早对话；容量交给模型调用前的自动压缩处理。 */
public final class AgentHistoryContext {
    private static final int PAGE_SIZE = 100;

    private AgentHistoryContext() {
    }

    public static boolean hasSummary(List<Msg> context) {
        return context.stream().anyMatch(AgentHistoryContext::isSummary);
    }

    /** 按原输入编号定位较早历史，不重复已有轮次，也不重新加入摘要已经覆盖的全文。 */
    public static List<Msg> restoreMissing(RunRecord run, List<Msg> context, ExecutionMessageMapper messages,
                                          RunMapper runs, ToolCallMapper calls) {
        if (hasSummary(context)) {
            return List.copyOf(context);
        }
        var before = messages.find(run.enterpriseId(), run.conversationId(), run.inputMessageId()).orElseThrow();
        var firstInput = context.stream().filter(message -> message.getRole() == MsgRole.USER).findFirst();
        if (!context.isEmpty()) {
            if (firstInput.isEmpty()) {
                return List.copyOf(context);
            }
            var boundary = messages.find(run.enterpriseId(), run.conversationId(), firstInput.get().getId())
                .filter(message -> message.role().equals("user"));
            if (boundary.isEmpty()) {
                return List.copyOf(context);
            }
            before = boundary.get();
        }
        var restored = new ArrayDeque<Msg>();
        while (true) {
            var page = messages.history(run.enterpriseId(), run.conversationId(), before, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            var historyRuns = page.stream().map(message -> message.runId()).filter(id -> id != null).distinct().toList();
            var sources = runs.sourceContexts(run.enterpriseId(), run.userId(), run.conversationId(), historyRuns);
            var callsByRun = calls.forConversationRuns(run.enterpriseId(), run.userId(), run.conversationId(), historyRuns)
                .stream().collect(Collectors.groupingBy(ToolCallRecord::runId,
                    Collectors.toMap(ToolCallRecord::stepId, call -> call)));
            for (var message : page) {
                String source = message.role().equals("user") && message.runId() != null ? sources.getOrDefault(message.runId(), "") : "";
                var mapped = AgentHistoryMessageMapper.map(message, source,
                    callsByRun.getOrDefault(message.runId(), Map.of()));
                if (!mapped.getTextContent().isBlank()) {
                    restored.addFirst(mapped);
                }
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
            var next = page.getLast();
            if (next.id().equals(before.id())) {
                throw new IllegalStateException("读取历史消息时位置没有前移，无法继续恢复对话");
            }
            before = next;
        }
        restored.addAll(context);
        return List.copyOf(restored);
    }

    private static boolean isSummary(Msg message) {
        return ConversationCompactor.SUMMARY_MSG_NAME.equals(message.getName());
    }
}
