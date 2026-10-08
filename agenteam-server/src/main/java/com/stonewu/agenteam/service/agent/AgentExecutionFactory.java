package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.agent.AgentHistoryMessageMapper;
import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.skill.SkillPromptMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.ExecutionLimits;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.agent.AgentModelFactory.ConfiguredModel;
import com.stonewu.agenteam.service.execution.RunCheckpointService;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.memory.MemoryPromptService;
import com.stonewu.agenteam.service.project.ProjectPromptService;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import com.stonewu.agenteam.service.user.UserLanguageService;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentDeclaration.Mode;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 在原有 HarnessAgent 对话流上按固定发布配置构造父子实例，不复用用户会话状态。
 */
@Component
public class AgentExecutionFactory {
    public record Execution(HarnessAgent agent, RuntimeContext context, EncryptedAgentStateStore states,
                            ExecutionGuardMiddleware guard, List<HarnessAgent> children, ExecutionToolRuntime tools,
                            boolean restored, Duration timeout) implements AutoCloseable {
        public void cancel() {
            guard.cancel();
            agent.getDelegate().interrupt(context);
        }

        @Override
        public void close() {
            tools.close();
            children.forEach(HarnessAgent::close);
            agent.close();
        }
    }

    private final AgentModelFactory models;
    private final ExecutionPaths paths;
    private final PayloadEncryption encryption;
    private final ObjectMapper json;
    private final RunLifecycleService lifecycle;
    private final RunMapper runs;
    private final ExecutionMessageMapper messages;
    private final RunCheckpointService checkpoints;
    private final ExecutionToolCatalog toolCatalog;
    private final ToolCallTransactions toolCalls;
    private final ToolExecutionService toolExecution;
    private final ToolCallMapper callRecords;
    private final ResourceJson resourceJson;
    private final MemoryPromptService memories;
    private final ProjectPromptService projects;
    private final UserLanguageService languages;

    public AgentExecutionFactory(AgentModelFactory models, ExecutionPaths paths, PayloadEncryption encryption,
                                 ObjectMapper json,
                                 RunLifecycleService lifecycle, RunMapper runs, ExecutionMessageMapper messages,
                                 RunCheckpointService checkpoints,
                                 ExecutionToolCatalog toolCatalog, ToolCallTransactions toolCalls,
                                 ToolExecutionService toolExecution,
                                 ToolCallMapper callRecords, ResourceJson resourceJson, MemoryPromptService memories,
                                 ProjectPromptService projects,
                                 UserLanguageService languages) {
        this.models = models;
        this.paths = paths;
        this.encryption = encryption;
        this.json = json;
        this.lifecycle = lifecycle;
        this.runs = runs;
        this.messages = messages;
        this.checkpoints = checkpoints;
        this.toolCatalog = toolCatalog;
        this.toolCalls = toolCalls;
        this.toolExecution = toolExecution;
        this.callRecords = callRecords;
        this.resourceJson = resourceJson;
        this.memories = memories;
        this.projects = projects;
        this.languages = languages;
    }

    public Execution create(RunRecord run, JobLease lease) {
        var config = run.executionConfig().path("config");
        boolean unsupported = false;
        for (var dependency : run.executionConfig().path("dependencies")) {
            if (!Set.of("agent", "skill", "plugin", "knowledge", "data").contains(dependency.path("kind").asText())) {
                unsupported = true;
            }
        }
        if (config.path("agentType").asText().equals("workflow") || unsupported) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
                "该员工暂时无法使用所配置的能力，请联系维护者。");
        }
        var states = store(run);
        boolean restored = checkpoints.restore(run, states);
        if (!restored) {
            initializeHistory(run, states);
        }
        return createScoped(run, lease, config, run.executionConfig().path("agentId").asText(null),
            run.executionConfig().path("name").asText(), run.conversationId(), states, restored,
            lifecycle.remaining(run), context -> checkpoints.captureLive(run, lease, states, context.getAgentState()));
    }

    /**
     * 节点使用独立框架会话，数据库记录、凭据、次数和资料授权仍属于真实顶层执行。
     */
    public Execution createScoped(RunRecord run, JobLease lease, JsonNode config, String agentId, String name,
                                  String session,
                                  EncryptedAgentStateStore states, boolean restored, Duration remaining,
                                  Consumer<RuntimeContext> checkpoint) {
        if (!Set.of("chat", "task").contains(config.path("agentType").asText())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "RESOURCE_DEPENDENCY_UNAVAILABLE", "当前流程节点需要对话型或任务型智能体。");
        }
        ConfiguredModel model = models.create(run.enterpriseId(), config.path("modelProfileId").asText());
        var guard = new ExecutionGuardMiddleware(lifecycle, lease, session, config.path("maxSteps").asInt());
        var limit = Duration.ofSeconds(config.path("timeoutSeconds").asInt());
        var timeout = ExecutionLimits.minTimeout(remaining, limit);
        Path workspace = session.equals(run.conversationId()) ? paths.workspace(run) : paths.workspace(run)
            .resolve("nodes").resolve(EncryptedAgentStateStore.component(session));
        var runtimeTools = new ExecutionToolRuntime(run, lease, tools(run, config, model), toolCalls, toolExecution,
            callRecords, resourceJson, timeout);
        runtimeTools.modelContext(session, model.getContextWindowSize());
        guard.tools(runtimeTools);
        guard.checkpoint(checkpoint);
        List<HarnessAgent> children = new CopyOnWriteArrayList<>();
        String projectInstructions = projects.instructions(run);
        String languageInstruction = languages.instruction(run.userId());
        var builder = builder(workspace, new ReadableToolModel(model, runtimeTools::modelToolNames), states, guard,
            config, timeout, run.id()).name(name)
            .sysPrompt(instructions(config) + memories.instructions(run, agentId, config)
                + (model.supportsTools() ? WorkspaceToolDefinitions.GUIDANCE : "") + projectInstructions + languageInstruction);
        var subagents = AgentSubagentConfigurationMapper.resolve(config, run.executionConfig().path("dependencies"));
        guard.subagents(subagents.stream().map(value -> value.id()).collect(Collectors.toSet()));
        for (var selected : subagents) {
            var definition = selected.definition();
            var childConfig = selected.config();
            var childModel = selected.resourceId() == null ? model : models.create(run.enterpriseId(),
                childConfig.path("modelProfileId").asText());
            var childLimit = Duration.ofSeconds(childConfig.path("timeoutSeconds").asInt());
            var childTimeout = ExecutionLimits.minTimeout(childLimit, timeout);
            String childInstructions = selected.resourceId() == null ? definition.instructions() : childInstructions(
                run, selected.resourceId(), childConfig);
            runtimeTools.childTools(definition.id(), tools(run, childConfig, childModel));
            int steps = Math.min(ExecutionLimits.frameworkMaxSteps(definition.maxSteps()),
                ExecutionLimits.frameworkMaxSteps(config.path("maxSteps").asInt()));
            var declaration = SubagentDeclaration.builder().name(definition.id())
                .description(definition.name() + "：" + definition.description())
                .steps(steps).persistSession(!definition.id().equals(AgentSubagentConfigurationMapper.DYNAMIC_ID))
                .inheritParentPermissions(true).mode(Mode.SUBAGENT).build();
            // 框架会按运行上下文重建管理器，必须在构造时注册工厂，不能事后只替换默认管理器。
            builder.subagent(declaration).subagentFactory(definition.id(), declaration.getDescription(), ignored -> {
                var child = builder(workspace, new ReadableToolModel(childModel, runtimeTools::modelToolNames), states,
                    guard, childConfig, childTimeout, run.id()).name(definition.name()).maxIters(steps)
                    .disableSubagents()
                    .workspace(prepare(workspace.resolve("children").resolve(UUID.randomUUID().toString())))
                    .sysPrompt(
                        childInstructions + (childModel.supportsTools() ? WorkspaceToolDefinitions.GUIDANCE : "") + projectInstructions + languageInstruction)
                    .build();
                AgentToolkitPolicy.retain(child, Set.of());
                runtimeTools.attachChild(child, definition.id());
                children.add(child);
                return child;
            });
        }
        if (subagents.isEmpty()) {
            builder.disableSubagents();
        }
        HarnessAgent agent = builder.build();
        AgentToolkitPolicy.retain(agent, subagents.isEmpty() ? Set.of() : Set.of("agent_spawn", "agent_send"));
        runtimeTools.attach(agent, false);
        if (!subagents.isEmpty()) {
            SubagentToolEvents.attach(agent, guard, subagents);
        }
        var context = RuntimeContext.builder().userId(run.userId()).sessionId(session)
            .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
            // 框架单次委派最多等待六百秒；零值不能传入，否则会退回三十秒默认值。
            .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS,
                timeout.isZero() ? 600 : (int) Math.max(1, timeout.toSeconds())).build();
        return new Execution(agent, context, states, guard, children, runtimeTools, restored, timeout);
    }

    private HarnessAgent.Builder builder(Path workspace, ConfiguredModel model, EncryptedAgentStateStore states,
                                         ExecutionGuardMiddleware guard, JsonNode config, Duration timeout,
                                         String runId) {
        var options = GenerateOptions.builder()
            .executionConfig(
                ExecutionConfig.builder().timeout(timeout.isZero() ? null : timeout).maxAttempts(1).build());
        if (model.supportsReasoning()) {
            options.maxCompletionTokens(model.maxOutputTokens());
        } else {
            options.maxTokens(model.maxOutputTokens());
        }
        if (config.has("temperature")) {
            options.temperature(config.path("temperature").asDouble());
        }
        if (config.hasNonNull("reasoningEffort")) {
            options.reasoningEffort(config.path("reasoningEffort").asText());
        }
        var generation = options.build();
        return HarnessAgent.builder().model(model).workspace(prepare(workspace)).stateStore(states).middleware(guard)
            .middleware(new CompactionSafetyMiddleware())
            .compaction(AgentContextCompaction.create(model, generation, runId, guard))
            .toolExecutionConfig(
                ExecutionConfig.builder().timeout(ExecutionLimits.frameworkToolTimeout(timeout)).maxAttempts(1).build())
            .maxIters(ExecutionLimits.frameworkMaxSteps(config.path("maxSteps").asInt())).generateOptions(generation)
            .disableFilesystemTools().disableShellTool().disableDynamicSkills().disableDefaultWorkspaceSkills()
            .disableDynamicSubagents().disableMemoryTools().disableMemoryHooks().disableToolsConfig()
            .disableWorkspaceContext().disableAtPathExpansion().disableSessionPersistence().disableToolResultEviction()
            .enableAgentTracingLog(false);
    }

    private Map<String, ExecutionToolBinding> tools(RunRecord run, JsonNode config, ConfiguredModel model) {
        var configured = toolCatalog.list(run, config);
        if (model.supportsTools()) {
            return configured.entrySet().stream().filter(entry -> model.supportsImages()
                    || !WorkspaceToolDefinitions.handles(entry.getValue()) || !entry.getValue().definition().name()
                    .equals("view_image"))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return configured.entrySet().stream().filter(entry -> !WorkspaceToolDefinitions.handles(entry.getValue()))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public EncryptedAgentStateStore store(RunRecord run) {
        return new EncryptedAgentStateStore(paths.state(run),
            run.enterpriseId() + ":" + run.userId() + ":" + run.id() + ":" + run.leaseVersion(), encryption, json);
    }

    public void initializeHistory(RunRecord run, EncryptedAgentStateStore states) {
        seedHistory(run, states, run.executionConfig().path("config").path("historyMessageLimit").asInt());
    }

    private void seedHistory(RunRecord run, EncryptedAgentStateStore states, int limit) {
        if (limit <= 0) {
            return;
        }
        List<Msg> context = new ArrayList<>();
        var toolContext = ToolContextState.builder().build();
        String previousId = run.executionConfig().path("previousRunId").asText(null);
        if (previousId != null) {
            var previous = runs.find(run.enterpriseId(), previousId, false)
                .filter(value -> value.userId().equals(run.userId())
                    && value.conversationId().equals(run.conversationId())).orElseThrow();
            var previousStates = checkpoints.load(previous);
            previousStates.get(run.userId(), run.conversationId(), "agent_state", AgentState.class).ifPresent(state -> {
                if (runs.latestTerminal(run.enterpriseId(), run.conversationId()).filter(previous.id()::equals)
                    .isPresent()) {
                    context.addAll(state.getContext());
                }
                var currentDefinitions = AgentSubagentConfigurationMapper.resolve(run.executionConfig().path("config"),
                        run.executionConfig().path("dependencies")).stream()
                    .collect(Collectors.toMap(value -> value.id(), value -> value.historyKey()));
                var previousDefinitions = AgentSubagentConfigurationMapper.resolve(
                        previous.executionConfig().path("config"), previous.executionConfig().path("dependencies")).stream()
                    .collect(Collectors.toMap(value -> value.id(), value -> value.historyKey()));
                if (!currentDefinitions.isEmpty()) {
                    for (var entry : state.getToolContext().getSpawnRegistry().entrySet()) {
                        var child = entry.getValue();
                        if (child.depth() != 1) {
                            throw new IllegalStateException("保存的子任务超出当前执行范围");
                        }
                        // 已删除或修改职责的助手不复用旧上下文，避免新配置继续执行旧任务。
                        var definition = currentDefinitions.get(child.agentId());
                        if (definition == null || !definition.equals(previousDefinitions.get(child.agentId()))) {
                            continue;
                        }
                        previousStates.get(run.userId(), child.sessionId(), "agent_state", AgentState.class)
                            .ifPresent(childState ->
                                states.save(run.userId(), child.sessionId(), "agent_state",
                                    AgentState.builder().userId(run.userId()).sessionId(child.sessionId())
                                        .context(recentContext(childState.getContext(), limit)).build()));
                        toolContext.putSpawnEntry(entry.getKey(), child);
                    }
                }
            });
        }
        if (context.isEmpty()) {
            var input = messages.find(run.enterpriseId(), run.conversationId(), run.inputMessageId()).orElseThrow();
            var history = messages.history(run.enterpriseId(), run.conversationId(), input, limit).reversed();
            var historyRuns = history.stream().map(message -> message.runId()).filter(value -> value != null).distinct()
                .toList();
            var callsByRun = callRecords.forConversationRuns(run.enterpriseId(), run.userId(), run.conversationId(),
                    historyRuns).stream()
                .collect(Collectors.groupingBy(ToolCallRecord::runId,
                    Collectors.toMap(ToolCallRecord::stepId, call -> call)));
            for (var message : history) {
                String sources = message.role().equals("user") && message.runId() != null ? runs.find(
                        run.enterpriseId(), message.runId(), false)
                    .filter(previous -> previous.userId().equals(run.userId()) && previous.conversationId()
                        .equals(run.conversationId()))
                    .map(previous -> previous.executionConfig().path("sourceContext").asText("")).orElse("") : "";
                var mapped = AgentHistoryMessageMapper.map(message, sources,
                    callsByRun.getOrDefault(message.runId(), Map.of()));
                if (!mapped.getTextContent().isBlank()) {
                    context.add(mapped);
                }
            }
        }
        var recent = recentContext(context, limit);
        if (!recent.isEmpty() || !toolContext.getSpawnRegistry().isEmpty()) {
            states.save(run.userId(), run.conversationId(), "agent_state", AgentState.builder()
                .userId(run.userId()).sessionId(run.conversationId()).context(recent).toolContext(toolContext).build());
        }
    }

    private List<Msg> recentContext(List<Msg> context, int limit) {
        int start = Math.max(0, context.size() - limit);
        while (start < context.size() && context.get(start).getRole() != MsgRole.USER) {
            start++;
        }
        return context.subList(start, context.size());
    }

    private String instructions(JsonNode config) {
        var result = new StringBuilder(config.path("instructions").asText());
        for (var term : config.path("businessTerms")) {
            result.append("\n").append(term.path("term").asText()).append("：").append(term.path("meaning").asText());
        }
        return result.toString();
    }

    private String childInstructions(RunRecord run, String resourceId, JsonNode config) {
        List<JsonNode> skills = new ArrayList<>();
        for (var id : config.path("skillVersionIds")) {
            for (var item : run.executionConfig().path("dependencies")) {
                if ("skill".equals(item.path("kind").asText()) && id.asText().equals(item.path("versionId").asText())) {
                    skills.add(item);
                }
            }
        }
        return new SkillPromptMapper().append(instructions(config) + memories.instructions(run, resourceId, config),
            skills);
    }

    private Path prepare(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException error) {
            throw new UncheckedIOException("无法准备本次执行的工作目录", error);
        }
    }
}
