package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.agent.AgentStreamEventMapper;
import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.model.user.entity.UserLanguage;
import com.stonewu.agenteam.service.agent.AgentModelFactory.ConfiguredModel;
import com.stonewu.agenteam.service.execution.RunCheckpointService;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.memory.MemoryPromptService;
import com.stonewu.agenteam.service.project.ConversationProjectService;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import com.stonewu.agenteam.service.project.ProjectPromptService;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.SourceToolDefinitions;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import com.stonewu.agenteam.service.user.UserLanguageService;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实际父子模型调用必须携带稳定身份，且不能继承平台未允许的工具。
 */
class AgentExecutionFactoryTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"dynamic,20,6,true,30,30", "both,20,6,true,30,30", "disabled,20,6,true,30,30", "unknown,20,6,true,30,30", "references,20,6,true,30,30",
        "dynamic,0,0,true,30,30", "references,0,0,true,30,30", "references,0,6,true,30,30", "references,20,0,true,30,30", "dynamic,500,6,true,30,30", "disabled,20,6,false,30,30",
        "dynamic,0,0,true,0,0", "references,0,0,true,0,0", "references,0,6,true,0,20", "references,20,0,true,30,0"})
    void everyParallelChildFragmentIdentifiesItsOwnSessionAndUsesIsolatedEncryptedState(String mode, int parentSteps, int childSteps, boolean supportsTools,
                                                                                        int parentSeconds, int childSeconds) throws Exception {
        var json = new ObjectMapper();
        var snapshot = json.createObjectNode().put("agentId", "employee").put("name", "资料员工");
        snapshot.put("workspaceProjectId", "project").put("workspaceProjectName", "共享报告")
            .put("workspaceProjectDirectory", "projects/reports");
        var config = snapshot.putObject("config").put("agentType", "chat").put("modelProfileId", "model")
            .put("instructions", "固定的执行指令").put("maxSteps", parentSteps).put("timeoutSeconds", parentSeconds)
            .put("historyMessageLimit", 0).put("researchSubagentEnabled", true).put("temperature", 0.4).put("icon", "Sparkles").put("color", "purple");
        config.putArray("businessTerms");
        snapshot.putArray("dependencies");
        config.put("dynamicSubagentEnabled", List.of("dynamic", "both", "unknown").contains(mode));
        String first = mode.equals("unknown") ? "unconfigured" : "general-purpose";
        String second = first;
        if (mode.equals("references") || mode.equals("both")) {
            var references = config.putArray("subagentVersionIds");
            for (String role : List.of("writer", "reviewer")) {
                references.add(role + "-version");
                var child = config.deepCopy().put("modelProfileId", "child-model").put("temperature", 0.8).put("businessRole", role)
                    .put("instructions", role.equals("writer") ? "写作规则甲：使用自己的知识库。" : "审校规则乙：使用自己的知识库。")
                    .put("maxSteps", childSteps).put("timeoutSeconds", childSeconds);
                child.put("icon", role.equals("writer") ? "NotebookPen" : "Telescope").put("color", role.equals("writer") ? "mint" : "blue");
                child.putArray("subagentVersionIds");
                child.put("researchSubagentEnabled", false);
                child.putArray("knowledgeVersionIds").add(role + "-knowledge");
                snapshot.withArray("dependencies").addObject().put("kind", "agent").put("versionId", role + "-version").put("resourceId", role)
                    .put("name", role.equals("writer") ? "写作助手" : "审校助手").set("config", child);
                snapshot.withArray("dependencies").addObject().put("kind", "knowledge").put("versionId", role + "-knowledge")
                    .put("resourceId", role + "-library").put("name", role + "资料库").putObject("config");
            }
            first = AgentSubagentConfigurationMapper.referenceId("writer-version");
            second = mode.equals("both") ? "general-purpose" : AgentSubagentConfigurationMapper.referenceId("reviewer-version");
        }
        var now = Instant.now();
        var run = new RunRecord("run", "enterprise", "conversation", "user", "input", "output", "version", "interactive", "running",
            snapshot, 1, 1, 4, false, 3, now, null, null, null, null, null, now);
        var lease = new JobLease("job", "enterprise", "user", "run", "worker", 4, now.plusSeconds(30));
        var models = mock(AgentModelFactory.class);
        ConfiguredModel model = model(first, second);
        when(model.supportsTools()).thenReturn(supportsTools);
        when(models.create("enterprise", "model")).thenReturn(model);
        if (mode.equals("references") || mode.equals("both")) {
            var childModel = model(first, second, true);
            when(models.create("enterprise", "child-model")).thenReturn(childModel);
        }
        var lifecycle = mock(RunLifecycleService.class);
        when(lifecycle.remaining(run)).thenReturn(Duration.ofSeconds(parentSeconds));
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String key = Base64.getEncoder().encodeToString(bytes);
        var encryption = new PayloadEncryption(new ApplicationSecretKeys(key, "{\"1\":\"" + key + "\"}", "1", directory.resolve("keys.properties").toString()), json);
        var paths = new ExecutionPaths(directory.resolve("workspace").toString(), directory.resolve("state").toString());
        var memories = mock(MemoryPromptService.class);
        when(memories.instructions(any(), any(), any())).thenReturn("");
        var languages = mock(UserLanguageService.class);
        when(languages.instruction(any())).thenReturn(UserLanguage.ENGLISH.instruction());
        var savedRuns = mock(RunMapper.class);
        var checkpoints = mock(RunCheckpointService.class);
        var toolExecution = mock(ToolExecutionService.class);
        var transactions = mock(ToolCallTransactions.class);
        var preparedCall = mock(ToolCallRecord.class);
        when(preparedCall.status()).thenReturn("prepared");
        when(transactions.prepare(any(), any(), any(), any(), any())).thenReturn(preparedCall);
        when(toolExecution.resultSettings()).thenReturn(new ToolResultSettings(32, 64, 16, 2, 8, 200, 2, 20));
        var availableVersions = mock(ResourceVersionMapper.class);
        when(availableVersions.available(any(), any())).thenAnswer(invocation -> Set.copyOf(invocation.<List<String>>getArgument(1)));
        when(toolExecution.invoke(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(json.createObjectNode().put("text", "已查询自己的资料库"));
        var factory = new AgentExecutionFactory(models, paths, encryption, json, lifecycle, savedRuns, mock(ExecutionMessageMapper.class), checkpoints,
            new ExecutionToolCatalog(mock(PluginToolMapper.class), new SourceToolDefinitions(new ResourceJson(json)), new WorkspaceToolDefinitions(new ResourceJson(json),
                new WorkspaceSettings(directory.resolve("files").toString(), "python:3.13-slim", true, 128, 20, 2000, 512, 1, 64, 120, "none")), availableVersions), transactions, toolExecution, mock(ToolCallMapper.class), new ResourceJson(json), memories,
            new ProjectPromptService(mock(ProjectMetadataService.class), mock(ConversationProjectService.class), json), languages);
        try (var execution = factory.create(run, lease)) {
            assertEquals(parentSteps == 0 ? Integer.MAX_VALUE : parentSteps, execution.agent().getDelegate().getMaxIters());
            assertEquals(Duration.ofSeconds(parentSeconds), execution.timeout());
            assertEquals(parentSeconds == 0 ? null : Duration.ofSeconds(parentSeconds),
                execution.agent().getDelegate().getGenerateOptions().getExecutionConfig().getTimeout());
            if (mode.equals("disabled") || mode.equals("unknown")) {
                var expected = mode.equals("disabled") ? Set.<String>of() : Set.of("agent_spawn", "agent_send");
                assertEquals(supportsTools ? withFileTools(expected, true) : expected, Set.copyOf(execution.agent().getToolkit().getToolNames()));
                var error = assertThrows(RuntimeException.class, () -> execution.agent().streamEvents(new UserMessage("父请求"), execution.context()).collectList().block(Duration.ofSeconds(10)));
                assertTrue(error.getMessage().contains("当前任务不能使用所请求的工具"));
                return;
            }
            assertEquals(withFileTools(Set.of("agent_spawn", "agent_send"), true), Set.copyOf(execution.agent().getToolkit().getToolNames()));
            for (var definition : AgentSubagentConfigurationMapper.resolve(config, snapshot.path("dependencies"))) {
                assertTrue(execution.agent().getSubagentAgentManager().hasAgent(definition.id()));
            }
            var parameters = execution.agent().getToolkit().getTool("agent_spawn").getParameters();
            var idParameter = (Map<?, ?>) ((Map<?, ?>) parameters.get("properties")).get("agent_id");
            assertEquals(AgentSubagentConfigurationMapper.resolve(config, snapshot.path("dependencies")).stream().map(value -> value.id()).toList(), idParameter.get("enum"));
            assertTrue(((List<?>) parameters.get("required")).contains("agent_id"));
            var events = execution.agent().streamEvents(new UserMessage("父请求"), execution.context()).collectList().block(Duration.ofSeconds(10));
            assertNotNull(events);
            Set<String> sessions = events.stream().filter(AgentStartEvent.class::isInstance).map(AgentStartEvent.class::cast)
                .filter(event -> event.getSource() != null).map(AgentStartEvent::getSessionId).collect(Collectors.toSet());
            assertEquals(2, sessions.size());
            var childText = events.stream().filter(TextBlockDeltaEvent.class::isInstance).filter(event -> event.getSource() != null).toList();
            assertTrue(childText.size() >= 2);
            for (var event : childText) {
                assertNotNull(event.getMetadata());
                String session = (String) event.getMetadata().get(ExecutionGuardMiddleware.SESSION_METADATA);
                assertTrue(sessions.contains(session), () -> new AgentStreamEventMapper().map(event).toString());
                String parentCall = (String) event.getMetadata().get(SubagentToolEvents.PARENT_CALL);
                assertTrue(Set.of("a", "b").contains(parentCall), () -> new AgentStreamEventMapper().map(event).toString());
            }
            for (var event : events.stream().filter(AgentStartEvent.class::isInstance).filter(event -> event.getSource() != null).toList()) {
                assertTrue(Set.of("a", "b").contains(event.getMetadata().get(SubagentToolEvents.PARENT_CALL)));
                boolean writer = event.getMetadata().get(SubagentToolEvents.PARENT_CALL).equals("a");
                boolean referenced = mode.equals("references") || mode.equals("both") && writer;
                assertEquals(referenced ? writer ? "NotebookPen" : "Telescope" : "Sparkles", event.getMetadata().get(SubagentToolEvents.ICON_METADATA));
                assertEquals(referenced ? writer ? "mint" : "blue" : "purple", event.getMetadata().get(SubagentToolEvents.COLOR_METADATA));
                if (mode.equals("references")) {
                    String expected = event.getMetadata().get(SubagentToolEvents.PARENT_CALL).equals("a") ? "写作助手（甲）" : "审校助手（乙）";
                    assertEquals(expected, event.getMetadata().get(SubagentToolEvents.LABEL));
                }
            }
            assertEquals(2, execution.children().size(), "真正调用的子实例必须来自平台注册的工厂");
            if (mode.equals("references")) {
                int expected = Math.min(parentSteps == 0 ? Integer.MAX_VALUE : parentSteps, childSteps == 0 ? Integer.MAX_VALUE : childSteps);
                for (var child : execution.children()) {
                    assertEquals(expected, child.getDelegate().getMaxIters());
                    int seconds = parentSeconds == 0 ? childSeconds : childSeconds == 0 ? parentSeconds : Math.min(parentSeconds, childSeconds);
                    assertEquals(seconds == 0 ? null : Duration.ofSeconds(seconds), child.getDelegate().getGenerateOptions().getExecutionConfig().getTimeout());
                }
            }
            if (mode.equals("references") || mode.equals("both")) {
                verify(toolExecution, times(mode.equals("both") ? 1 : 2)).invoke(eq(run), eq(lease), any(), any(), any(), any(), any(), any(), anyInt(), any());
            }
            for (var child : execution.children()) {
                if ((mode.equals("references") || mode.equals("both")) && !child.getName().equals("临时助手")) {
                    var role = child.getName().equals("写作助手") ? "writer" : "reviewer";
                    var alias = "platform_knowledge_search_" + role + "knowledge";
                    var otherAlias = "platform_knowledge_search_" + (role.equals("writer") ? "reviewer" : "writer") + "knowledge";
                    assertEquals(withFileTools(Set.of(alias)), Set.copyOf(child.getToolkit().getToolNames()));
                    assertTrue(execution.tools().allowed(child.getDelegate(), alias, true));
                    assertTrue(!execution.tools().allowed(child.getDelegate(), otherAlias, true));
                    assertTrue(!execution.tools().allowed(execution.agent().getDelegate(), alias, false));
                } else {
                    assertEquals(withFileTools(Set.of()), Set.copyOf(child.getToolkit().getToolNames()));
                }
                assertTrue(child.getStateStore() instanceof EncryptedAgentStateStore);
            }
            assertTrue(execution.states().get("user", "conversation", "agent_state", AgentState.class).isPresent());
            verify(lifecycle, atLeastOnce()).beforeExternalStep(lease);
            when(savedRuns.find("enterprise", "run", false)).thenReturn(Optional.of(run));
            when(savedRuns.latestTerminal("enterprise", "conversation")).thenReturn(Optional.of(run.id()));
            when(checkpoints.load(run)).thenReturn(execution.states());
            var nextSnapshot = snapshot.deepCopy().put("previousRunId", run.id());
            nextSnapshot.withObject("config").put("historyMessageLimit", 20);
            var next = new RunRecord("next", "enterprise", "conversation", "user", "input-next", "output-next", "version", "interactive", "running",
                nextSnapshot, 1, 1, 5, false, 1, now, null, null, null, null, null, now);
            var nextStates = factory.store(next);
            factory.initializeHistory(next, nextStates);
            assertEquals(2, nextStates.get("user", "conversation", "agent_state", AgentState.class).orElseThrow().getToolContext().getSpawnRegistry().size(), "配置不变时保留各自的子会话");
            nextSnapshot.put("workspaceProjectId", "second-project").put("workspaceProjectName", "新项目")
                .put("workspaceProjectDirectory", "projects/second");
            try (var continued = factory.createScoped(next, lease, nextSnapshot.path("config"), "employee", "资料员工", "conversation",
                nextStates, true, Duration.ofSeconds(30), ignored -> {
                })) {
                assertNotNull(continued.agent().streamEvents(new UserMessage("切换项目后的请求"), continued.context())
                    .collectList().block(Duration.ofSeconds(10)));
            }
            if (mode.equals("references") || mode.equals("both")) {
                nextSnapshot.withArray("dependencies").get(0).withObject("config").put("instructions", "已修改的版本配置");
                factory.initializeHistory(next, nextStates);
                assertEquals(1, nextStates.get("user", "conversation", "agent_state", AgentState.class).orElseThrow().getToolContext().getSpawnRegistry().size());
            }
            nextSnapshot.withObject("config").putArray("subagentVersionIds");
            nextSnapshot.withObject("config").put("dynamicSubagentEnabled", false);
            factory.initializeHistory(next, nextStates);
            assertTrue(nextStates.get("user", "conversation", "agent_state", AgentState.class).orElseThrow().getToolContext().getSpawnRegistry().isEmpty(), "移除全部助手后不恢复旧调用入口");
        }
        try (var files = Files.walk(paths.state(run))) {
            assertTrue(files.filter(Files::isRegularFile).allMatch(path -> {
                try {
                    return !Files.readString(path).contains("子问题");
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }));
        }
    }

    private ConfiguredModel model(String first, String second) {
        return model(first, second, false);
    }

    private Set<String> withFileTools(Set<String> additional) {
        return withFileTools(additional, false);
    }

    private Set<String> withFileTools(Set<String> additional, boolean writable) {
        var names = new HashSet<>(additional);
        names.addAll(Set.of("platform_read_file_version", "platform_grep_files_version", "platform_list_files_version"));
        if (writable) {
            names.addAll(Set.of("platform_write_file_version", "platform_edit_file_version", "platform_execute_version", "platform_export_file_version"));
        }
        return Set.copyOf(names);
    }

    private ConfiguredModel model(String first, String second, boolean referenced) {
        ConfiguredModel model = mock(ConfiguredModel.class);
        when(model.getModelName()).thenReturn("isolated-factory-model");
        when(model.supportsTools()).thenReturn(true);
        when(model.getContextWindowSize()).thenReturn(262144);
        when(model.maxOutputTokens()).thenReturn(referenced ? 4096 : 204800);
        AtomicInteger calls = new AtomicInteger();
        when(model.stream(any(), any(), any())).thenAnswer(call -> {
            GenerateOptions options = call.getArgument(2);
            assertEquals(referenced ? 4096 : 204800, options.getMaxTokens(), "模型调用应使用对应智能体配置中的最大输出长度");
            assertEquals(referenced ? 0.8 : 0.4, options.getTemperature());
            List<Msg> input = call.getArgument(0);
            String projectSystem = input.stream().filter(value -> value.getRole() == MsgRole.SYSTEM)
                .map(Msg::getTextContent).collect(Collectors.joining("\n"));
            assertTrue(projectSystem.contains(UserLanguage.ENGLISH.instruction()), "父智能体与实际子智能体请求必须包含用户回复语言");
            String question = input.stream().filter(value -> value.getRole() == MsgRole.USER).map(Msg::getTextContent).reduce((a, b) -> b).orElse("");
            assertTrue(projectSystem.contains("未经用户明确同意，不得读取、列出、搜索、修改或删除当前项目之外的用户内容"));
            if (question.equals("切换项目后的请求")) {
                assertTrue(projectSystem.contains("新项目"));
                assertTrue(projectSystem.contains("/workspace/projects/second"));
                assertTrue(!projectSystem.contains("/workspace/projects/reports"));
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("继续新项目").build())).finishReason("stop").build());
            }
            assertTrue(projectSystem.contains("共享报告"));
            assertTrue(projectSystem.contains("/workspace/projects/reports"));
            if (!question.equals("父请求")) {
                String id = question.contains("子问题甲") ? first : second;
                String system = input.stream().filter(value -> value.getRole() == MsgRole.SYSTEM).map(Msg::getTextContent).collect(Collectors.joining("\n"));
                if (id.equals("writer") || id.equals(AgentSubagentConfigurationMapper.referenceId("writer-version"))) {
                    assertTrue(system.contains("写作规则甲"));
                }
                if (id.equals("reviewer") || id.equals(AgentSubagentConfigurationMapper.referenceId("reviewer-version"))) {
                    assertTrue(system.contains("审校规则乙"));
                }
                if (id.equals("general-purpose")) {
                    assertTrue(system.contains("本次任务说明中指定的职责"));
                    assertTrue(question.contains("校对助手"));
                }
                if (referenced && input.stream().noneMatch(message -> message.getContent().stream().anyMatch(ToolResultBlock.class::isInstance))) {
                    String role = question.contains("子问题甲") ? "writer" : "reviewer";
                    return Flux.just(ChatResponse.builder().content(List.of(ToolUseBlock.builder().id("lookup-" + role)
                        .name("platform_knowledge_search_" + role + "knowledge").content("{\"query\":\"当前任务\"}").build())).finishReason("tool_calls").build());
                }
            }
            if (question.equals("父请求") && calls.getAndIncrement() == 0) {
                return Flux.just(ChatResponse.builder().content(List.of(
                        ToolUseBlock.builder().id("a").name("agent_spawn").content("{\"agent_id\":\"%s\",\"label\":\"甲\",\"task\":\"请作为校对助手处理子问题甲\"}".formatted(first)).build(),
                        ToolUseBlock.builder().id("b").name("agent_spawn").content("{\"agent_id\":\"%s\",\"label\":\"乙\",\"task\":\"请作为校对助手处理子问题乙\"}".formatted(second)).build()))
                    .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(question.equals("父请求") ? "父结果" : question + "结果").build()))
                .finishReason("stop").build()).delayElements(Duration.ofMillis(10));
        });
        return model;
    }
}
