package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper;
import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper.ResolvedSubagent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从实际工具调用取得父编号，子任务生命周期和文本事件共用这一明确关系。
 */
public final class SubagentToolEvents implements AgentTool {
    public static final String PARENT_CALL = "agenteamParentToolCallId";
    public static final String LABEL = "agenteamSubagentLabel";
    public static final String CHILD_STATUS = "agenteamChildStatus";
    public static final String ICON_METADATA = "agenteamAgentIcon";
    public static final String COLOR_METADATA = "agenteamAgentColor";
    private final AgentTool delegate;
    private final ExecutionGuardMiddleware guard;
    private final Map<String, Object> parameters;
    private final Map<String, String> names;
    private final Map<String, JsonNode> appearances;

    private SubagentToolEvents(AgentTool delegate, ExecutionGuardMiddleware guard, List<ResolvedSubagent> subagents) {
        this.delegate = delegate;
        this.guard = guard;
        names = new LinkedHashMap<>();
        appearances = new LinkedHashMap<>();
        for (var value : subagents) {
            names.put(value.id(), value.definition().name());
            appearances.put(value.id(), value.config());
        }
        parameters = new LinkedHashMap<>(delegate.getParameters());
        if (delegate.getName().equals("agent_spawn") && parameters.get("properties") instanceof Map<?, ?> original) {
            Map<String, Object> properties = new LinkedHashMap<>();
            original.forEach((key, value) -> properties.put(key.toString(), value));
            properties.put("agent_id",
                Map.of("type", "string", "enum", List.copyOf(names.keySet()), "description", "选择当前员工已配置的助手："
                    + String.join("；", subagents.stream()
                    .map(value -> value.id() + "：" + value.definition().name() + "，" + value.definition().description())
                    .toList())));
            parameters.put("properties", properties);
            var required = new ArrayList<String>();
            if (parameters.get("required") instanceof List<?> fields) {
                fields.forEach(value -> required.add(value.toString()));
            }
            if (!required.contains("agent_id")) {
                required.add("agent_id");
            }
            parameters.put("required", required);
        }
    }

    public static void attach(HarnessAgent agent, ExecutionGuardMiddleware guard, List<ResolvedSubagent> subagents) {
        for (String name : List.of("agent_spawn", "agent_send")) {
            var tool = agent.getToolkit().getTool(name);
            if (tool == null) {
                continue;
            }
            agent.getToolkit().removeTool(name);
            agent.getToolkit().registerAgentTool(new SubagentToolEvents(tool, guard, subagents));
        }
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.deferContextual(context -> {
            var emitter = AgentEventEmitter.fromContext(context)
                .orElseThrow(() -> new IllegalStateException("子任务必须使用带有事件身份的执行连接"));
            String call = param.getToolUseBlock().getId();
            Object label = param.getInput().get("label");
            Object agentId = param.getInput().get("agent_id");
            String configuredName = names.get(agentId);
            String taskLabel = label instanceof String name && !name.isBlank() ? name : null;
            String displayName = configuredName == null || AgentSubagentConfigurationMapper.DYNAMIC_ID.equals(
                agentId) ? taskLabel
                : taskLabel == null || taskLabel.equals(
                configuredName) ? configuredName : configuredName + "（" + taskLabel + "）";
            if (displayName == null) {
                displayName = configuredName;
            }
            String eventLabel = displayName;
            var childSession = new AtomicReference<String>();
            var childAppearance = new AtomicReference<JsonNode>();
            AgentEventEmitter tagged = event -> {
                // 工具结果事件可能由框架直接转发，使用本次子任务启动事件给出的真实会话身份。
                if (event instanceof AgentStartEvent start) {
                    childSession.set(start.getSessionId());
                    // 框架的启动事件名称为已注册助手标识，启动和继续调用均可据此匹配固定配置。
                    childAppearance.set(appearances.get(start.getName()));
                }
                var appearance = childAppearance.get();
                if (appearance != null && appearance.path("icon").isTextual()) {
                    event.withMetadataEntry(ICON_METADATA, appearance.path("icon").asText());
                    if (appearance.path("color").isTextual()) {
                        event.withMetadataEntry(COLOR_METADATA, appearance.path("color").asText());
                    }
                }
                String session = childSession.get();
                if (session != null) {
                    event.withMetadataEntry(ExecutionGuardMiddleware.SESSION_METADATA, session);
                }
                event.withMetadataEntry(PARENT_CALL, call);
                String status = guard.childOutcome(call);
                if (status != null) {
                    event.withMetadataEntry(CHILD_STATUS, status);
                }
                if (eventLabel != null) {
                    event.withMetadataEntry(LABEL, eventLabel);
                }
                emitter.emit(event);
            };
            return delegate.callAsync(param)
                .contextWrite(next -> next.put(AgentEventEmitter.CONTEXT_KEY, tagged).put(PARENT_CALL, call));
        });
    }
}
