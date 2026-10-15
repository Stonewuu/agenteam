package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.response.MessageView;
import com.stonewu.agenteam.service.execution.RunCheckpointService;
import com.stonewu.agenteam.service.agent.AgentModelFactory.ConfiguredModel;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.memory.MemoryPromptService;
import com.stonewu.agenteam.service.project.ProjectPromptService;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import com.stonewu.agenteam.service.user.UserLanguageService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证真实加密状态在换轮时完整保留用户要求与工具结果，不受旧历史条数设置影响。 */
class AgentHistoryRestorationTest {
    @TempDir
    Path directory;

    @Test
    void longToolRoundIsRetainedWhenInternalMessagesExceedHistoryLimit() {
        var context = longRound("previous-input", 30);
        var fixture = fixture(context, 20, List.of());
        fixture.factory().initializeHistory(fixture.next(), fixture.target());
        var saved = fixture.target().get("user", "conversation", "agent_state", AgentState.class);
        assertTrue(saved.isPresent(), "长工具循环不能让上一轮历史变成空列表");
        assertEquals(context.stream().map(Msg::getId).toList(), saved.orElseThrow().getContext().stream().map(Msg::getId).toList());
        assertEquals(30, saved.orElseThrow().getContext().stream().filter(message -> message.getRole() == MsgRole.TOOL).count());
    }

    @Test
    void recordedEarlierTurnsRepairAFormerlyTruncatedCheckpointWithoutRepeatingTheRetainedTurn() {
        var recent = List.of(text("recent-input", MsgRole.USER, "继续核对文件"), text("recent-answer", MsgRole.ASSISTANT, "已核对最新文件"));
        var history = List.of(view("old-input", "user", "合成要求：交付包含三张表"), view("old-answer", "assistant", "合成结果：考勤已统计"),
            view("recent-input", "user", "继续核对文件"), view("recent-output", "assistant", "已核对最新文件"));
        var fixture = fixture(recent, 20, history);
        fixture.factory().initializeHistory(fixture.next(), fixture.target());
        var saved = fixture.target().get("user", "conversation", "agent_state", AgentState.class).orElseThrow();
        assertEquals(List.of("old-input", "old-answer", "recent-input", "recent-answer"), saved.getContext().stream().map(Msg::getId).toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 20, 100})
    void legacyHistorySettingsNeverDiscardSavedTurns(int legacyLimit) {
        var context = new ArrayList<Msg>();
        for (int turn = 0; turn < 120; turn++) {
            context.addAll(longRound("input-" + turn, 1));
        }
        var fixture = fixture(context, legacyLimit, List.of());
        fixture.factory().initializeHistory(fixture.next(), fixture.target());
        var saved = fixture.target().get("user", "conversation", "agent_state", AgentState.class).orElseThrow();
        assertEquals(context.stream().map(Msg::getId).toList(), saved.getContext().stream().map(Msg::getId).toList());
    }

    @Test
    void compressedSummaryAndItsLongToolTailRemainAvailableWithoutReintroducingRawHistory() {
        var context = longRound("summary", 30);
        context.set(0, Msg.builder().id("summary").name(ConversationCompactor.SUMMARY_MSG_NAME).role(MsgRole.USER)
            .content(TextBlock.builder().text("合成摘要：保留交付目标及既有约束").build()).build());
        var fixture = fixture(context, 20, List.of(view("old-input", "user", "压缩前的旧正文")));
        fixture.factory().initializeHistory(fixture.next(), fixture.target());
        var saved = fixture.target().get("user", "conversation", "agent_state", AgentState.class).orElseThrow();
        assertEquals(context.stream().map(Msg::getId).toList(), saved.getContext().stream().map(Msg::getId).toList());
        verifyNoInteractions(fixture.messages());
    }

    @Test
    void nextModelRequestActuallyReceivesTheEarlierRequirementsAndToolResults() {
        var fixture = fixture(longRound("previous-input", 30), 20, List.of());
        var now = Instant.now();
        var lease = new JobLease("job", "enterprise", "user", "next", "worker", 1, now.plusSeconds(30));
        try (var execution = fixture.factory().create(fixture.next(), lease)) {
            execution.agent().streamEvents(UserMessage.builder().id("next-input").textContent("合成后续要求：继续按上次约束处理").build(),
                execution.context()).collectList().block(Duration.ofSeconds(10));
        }
        assertEquals(1, fixture.requests().size());
        var input = fixture.requests().getFirst();
        assertTrue(input.stream().anyMatch(message -> "previous-input".equals(message.getId())
            && message.getTextContent().contains("保留工资与考勤的核对依据")));
        assertEquals(30, input.stream().filter(message -> message.getRole() == MsgRole.TOOL).count());
        assertTrue(input.stream().anyMatch(message -> "next-input".equals(message.getId())));
    }

    private Fixture fixture(List<Msg> context, int limit, List<MessageView> history) {
        var json = new ObjectMapper();
        var config = json.createObjectNode().put("agentId", "employee").put("name", "历史验证员工");
        config.putObject("config").put("agentType", "chat")
            .put("modelProfileId", "model").put("maxSteps", 2).put("timeoutSeconds", 30).put("instructions", "只处理合成测试数据");
        if (limit >= 0) {
            config.withObject("config").put("historyMessageLimit", limit);
        }
        config.putArray("dependencies");
        var now = Instant.parse("2026-10-14T10:00:00Z");
        var previous = new RunRecord("previous", "enterprise", "conversation", "user", "previous-input", "previous-output", "version",
            "interactive", "completed", config, 1, 1, 1, false, 1, now, null, null, null, null, null, now);
        var next = new RunRecord("next", "enterprise", "conversation", "user", "next-input", "next-output", "version",
            "interactive", "running", config.deepCopy().put("previousRunId", previous.id()), 1, 1, 1, false, 1, now, null, null, null, null, null, now);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String key = Base64.getEncoder().encodeToString(bytes);
        var encryption = new PayloadEncryption(new ApplicationSecretKeys(key, "{\"1\":\"" + key + "\"}", "1", directory.resolve("keys.properties").toString()), json);
        var paths = new ExecutionPaths(directory.resolve("workspace").toString(), directory.resolve("states").toString());
        var runs = mock(RunMapper.class);
        when(runs.find("enterprise", previous.id(), false)).thenReturn(Optional.of(previous));
        when(runs.latestTerminal("enterprise", "conversation")).thenReturn(Optional.of(previous.id()));
        var messages = mock(ExecutionMessageMapper.class);
        when(messages.find(any(), any(), any())).thenAnswer(call -> {
            String id = call.getArgument(2);
            return id.equals("next-input") ? Optional.of(view(id, "user", "继续处理"))
                : history.stream().filter(message -> message.id().equals(id)).findFirst();
        });
        when(messages.history(any(), any(), any(), anyInt())).thenAnswer(call -> {
            MessageView before = call.getArgument(2);
            int index = history.indexOf(before);
            int end = index < 0 ? history.size() : index;
            int pageSize = call.getArgument(3);
            return history.subList(Math.max(0, end - pageSize), end).reversed();
        });
        var checkpoints = mock(RunCheckpointService.class);
        var requests = new ArrayList<List<Msg>>();
        var model = mock(ConfiguredModel.class);
        when(model.getModelName()).thenReturn("history-verification-model");
        when(model.getContextWindowSize()).thenReturn(262144);
        when(model.maxOutputTokens()).thenReturn(1024);
        when(model.stream(any(), any(), any())).thenAnswer(call -> {
            requests.add(List.copyOf(call.getArgument(0)));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("合成验证完成").build())).finishReason("stop").build());
        });
        var models = mock(AgentModelFactory.class);
        when(models.create("enterprise", "model")).thenReturn(model);
        var lifecycle = mock(RunLifecycleService.class);
        when(lifecycle.remaining(next)).thenReturn(Duration.ofSeconds(30));
        var memories = mock(MemoryPromptService.class);
        when(memories.instructions(any(), any(), any())).thenReturn("");
        var projects = mock(ProjectPromptService.class);
        when(projects.instructions(any())).thenReturn("");
        var languages = mock(UserLanguageService.class);
        when(languages.instruction(any())).thenReturn("");
        var toolExecution = mock(ToolExecutionService.class);
        when(toolExecution.resultSettings()).thenReturn(new ToolResultSettings(32, 64, 2, 8, 200, 2, 20));
        var factory = new AgentExecutionFactory(models, paths, encryption, json, lifecycle, runs,
            messages, checkpoints, mock(ExecutionToolCatalog.class), mock(ToolCallTransactions.class), toolExecution,
            mock(ToolCallMapper.class), new ResourceJson(json), memories, projects, languages);
        var source = factory.store(previous);
        source.save("user", "conversation", "agent_state", AgentState.builder().userId("user").sessionId("conversation")
            .context(context).toolContext(ToolContextState.builder().build()).build());
        when(checkpoints.load(previous)).thenReturn(source);
        return new Fixture(factory, next, factory.store(next), checkpoints, messages, requests);
    }

    static List<Msg> longRound(String userId, int tools) {
        var context = new ArrayList<Msg>();
        context.add(text(userId, MsgRole.USER, "合成要求：保留工资与考勤的核对依据"));
        for (int index = 0; index < tools; index++) {
            String call = userId + "-call-" + index;
            context.add(Msg.builder().id(call).role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id(call).name("lookup")
                .content("{\"query\":\"合成数据\"}").build()).build());
            context.add(Msg.builder().id(call + "-result").role(MsgRole.TOOL).content(ToolResultBlock.builder().id(call).name("lookup")
                .output(TextBlock.builder().text("合成结果 " + index).build()).build()).build());
        }
        context.add(text(userId + "-answer", MsgRole.ASSISTANT, "合成结论：已完成核对"));
        return context;
    }

    static Msg text(String id, MsgRole role, String text) {
        return Msg.builder().id(id).role(role).content(TextBlock.builder().text(text).build()).build();
    }

    private MessageView view(String id, String role, String content) {
        return new MessageView(id, null, 1, role, content, "completed", List.of(), List.of(), null,
            "2026-10-14T09:00:00Z", "2026-10-14T09:00:00Z");
    }

    private record Fixture(AgentExecutionFactory factory, RunRecord next, EncryptedAgentStateStore target,
                           RunCheckpointService checkpoints, ExecutionMessageMapper messages, List<List<Msg>> requests) {
    }
}
