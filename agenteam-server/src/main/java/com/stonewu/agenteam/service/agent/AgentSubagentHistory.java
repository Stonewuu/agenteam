package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.i18n.Messages;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;

import java.util.List;
import java.util.stream.Collectors;

/** 普通续聊和失败重试共用子智能体配置检查及会话恢复。 */
public final class AgentSubagentHistory {
    private AgentSubagentHistory() {
    }

    public static ToolContextState restore(RunRecord run, RunRecord previous, EncryptedAgentStateStore previousStates,
                                           AgentState previousState, EncryptedAgentStateStore target) {
        var result = ToolContextState.builder().build();
        var currentDefinitions = AgentSubagentConfigurationMapper.resolve(run.executionConfig().path("config"),
                run.executionConfig().path("dependencies")).stream()
            .collect(Collectors.toMap(value -> value.id(), value -> value.historyKey()));
        var previousDefinitions = AgentSubagentConfigurationMapper.resolve(previous.executionConfig().path("config"),
                previous.executionConfig().path("dependencies")).stream()
            .collect(Collectors.toMap(value -> value.id(), value -> value.historyKey()));
        if (currentDefinitions.isEmpty()) {
            return result;
        }
        for (var entry : previousState.getToolContext().getSpawnRegistry().entrySet()) {
            var child = entry.getValue();
            if (child.depth() != 1) {
                throw new IllegalStateException(Messages.text("error.agent.agentExecutionFactory.initializeHistory.1"));
            }
            // 已删除或修改职责的助手不复用旧上下文，避免新配置继续执行旧任务。
            var definition = currentDefinitions.get(child.agentId());
            if (definition == null || !definition.equals(previousDefinitions.get(child.agentId()))) {
                continue;
            }
            previousStates.get(run.userId(), child.sessionId(), "agent_state", AgentState.class)
                .ifPresent(childState -> target.save(run.userId(), child.sessionId(), "agent_state",
                    AgentState.builder().userId(run.userId()).sessionId(child.sessionId())
                        .context(List.copyOf(childState.getContext())).build()));
            result.putSpawnEntry(entry.getKey(), child);
        }
        return result;
    }
}
