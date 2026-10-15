package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.request.NewConversationInput;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.collaboration.CollaborationRunObserver;
import com.stonewu.agenteam.service.collaboration.CollaborationGeneratedResultService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.ConversationWorkspaceStore;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 通过正式 Run 提交、模型执行和独立终态归并验证岗位工作。 */
class CollaborationExecutionTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private CollaborationDispatchService dispatch;
    @Autowired private CollaborationRunObserver observer;
    @Autowired private RunSubmissionService submissions;
    @Autowired private RunMapper runs;
    @Autowired private ChatIdentityService identities;
    @Autowired private CollaborationGeneratedResultService generated;
    @Autowired private FileAccessService fileAccess;
    @Autowired private FileContentStorage storage;
    @Autowired private ConversationQueryService conversationQueries;
    @Autowired private ConversationWorkspaceStore workspaces;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @AfterEach
    void stopOwnedRuns() {
        lifecycle.stopForUser(enterprise, initiator.id());
    }

    @Test
    void submissionUsesOriginalUserFixedVersionTaskProjectAndOneRunLink() {
        var incoming = receive("submission", "统计考勤");
        routing.route(enterprise, incoming.getId());
        var task = onlyTask();
        var lease = dispatch.claim("test-dispatch").orElseThrow();
        var accepted = dispatch.submit(lease);
        var run = runs.find(enterprise, accepted.runId(), false).orElseThrow();
        assertEquals(initiator.id(), run.userId());
        assertEquals(version, run.agentVersionId());
        assertEquals("interactive", run.mode());
        assertEquals("collaboration", run.executionConfig().path("sourceType").asText());
        assertEquals("full_access", run.executionConfig().path("approvalPolicy").asText());
        var role = role(task.getOwnerParticipantId());
        assertNotNull(role.getProjectId());
        assertEquals(role.getProjectId(), run.executionConfig().path("workspaceProjectId").asText());
        assertEquals(accepted.conversationId(), role.getConversationId());
        assertEquals("collaboration", databaseAccess.mapper(ConversationSqlMapper.class).selectById(role.getConversationId()).getSourceType());
        var link = link(run.id());
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var snapshot = conversationQueries.snapshot(actor, role.getConversationId());
        assertEquals(task.getId(), snapshot.conversation().collaborationTaskId());
        assertFalse(snapshot.conversation().canContinue());
        var userMessage = snapshot.messages().stream().filter(message -> message.role().equals("user")).findFirst().orElseThrow();
        assertTrue(userMessage.content().contains("统计考勤"));
        assertFalse(userMessage.content().contains(task.getId()));
        assertFalse(userMessage.content().contains("inputRevision"));
        assertTrue(run.executionConfig().path("sourceContext").asText().contains(task.getId()));
        assertFalse(run.executionConfig().has("collaborationInputContext"));
        assertEquals(task.getId(), conversationQueries.list(actor, null, null, null, null, null, 30).items().stream()
            .filter(value -> value.id().equals(role.getConversationId())).findFirst().orElseThrow().collaborationTaskId());
        assertEquals(run.id(), submissions.collaboration(actor, lease, link.getContextId(), link.getRunRound()).runId());
        assertEquals(1, databaseAccess.mapper(CollaborationRunLinkMapper.class).selectCount(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getTaskId, task.getId())));
        assertEquals(1, onlyTask().getRunCount());
        assertEquals("COLLABORATION_INPUT_REQUIRED", assertThrows(ApiException.class,
            () -> submissions.send(actor, role.getConversationId(), new MessageInput("绕过任务直接修改", List.of(), List.of(), List.of(), List.of()))).code());
    }

    @Test
    void actualModelAnswerBecomesPrivateArtifactAndCompletesTaskExactlyOnce() throws Exception {
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var personal = submissions.create(actor, new NewConversationInput(agent,
            new MessageInput("个人对话私密标记，不属于群任务", List.of(), List.of(), List.of(), List.of())));
        var personalRun = runs.find(enterprise, personal.runId(), false).orElseThrow();
        try (var control = new ToolCallControl(() -> {
        }); var scope = workspaces.open(personalRun, personalRun.conversationId(), control, Duration.ofSeconds(10), () -> {
        })) {
            scope.prepare().write(RuntimeContext.empty(), "private-payroll.txt", "个人项目私密工资标记，不得混入协作上下文");
            scope.commit(Set.of(), Set.of());
        }
        lifecycle.stopForUser(enterprise, initiator.id());
        // 仅链接交付由群设置决定，普通业务正文不会隐式修改回复方式。
        assertEquals(1, databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, group.getId())
            .set(ChatConversationRow::getResultPolicy, "summary_link")));
        var incoming = receive("answer", "整理本月考勤，工资数据不得直接公开到群");
        routing.route(enterprise, incoming.getId());
        var accepted = dispatch.submit(dispatch.claim("model-dispatch").orElseThrow());
        var captured = new AtomicReference<JsonNode>();
        allowModelCalls = true;
        modelResponse = exchange -> {
            captured.set(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var delta = Map.of("id", "collaboration-model", "choices", List.of(Map.of("index", 0,
                "delta", Map.of("content", "已核对测试资料，考勤合计为二十天。"), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(delta) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.runId(), false).orElseThrow().terminal());
        }
        assertEquals("completed", runs.find(enterprise, accepted.runId(), false).orElseThrow().status());
        assertNotNull(captured.get());
        var roleRun = runs.find(enterprise, accepted.runId(), false).orElseThrow();
        assertNotEquals(personal.conversationId(), roleRun.conversationId());
        assertNotEquals(personalRun.executionConfig().path("workspaceProjectId"), roleRun.executionConfig().path("workspaceProjectId"));
        assertNotEquals(personalRun.executionConfig().path("workspaceProjectDirectory"), roleRun.executionConfig().path("workspaceProjectDirectory"));
        assertFalse(captured.get().toString().contains("个人对话私密标记"));
        assertFalse(captured.get().toString().contains("个人项目私密工资标记"));
        for (var message : captured.get().path("messages")) {
            if (message.path("role").asText().equals("system")) {
                assertFalse(message.toString().contains("工资数据不得直接公开到群"), "用户正文不能提升成系统指令");
            }
        }
        var link = link(accepted.runId());
        observer.observe(enterprise, link.getId());
        observer.observe(enterprise, link.getId());
        var task = onlyTask();
        assertEquals("completed", task.getStatus());
        assertNotNull(task.getFinalArtifactId());
        var work = databaseAccess.mapper(CollaborationWorkItemMapper.class).selectById(task.getOwnerWorkId());
        assertEquals("completed", work.getStatus());
        assertNull(work.getActiveRunId());
        assertNotNull(link(accepted.runId()).getTerminalObservedAt());
        assertEquals("summary_link", task.getOutputVisibility());
    }

    @Test
    void staleDispatchLeaseCannotCreateAnotherConversation() {
        var incoming = receive("stale", "简单任务");
        routing.route(enterprise, incoming.getId());
        var lease = dispatch.claim("first").orElseThrow();
        var accepted = dispatch.submit(lease);
        assertNull(dispatch.submit(lease));
        assertEquals(1, onlyTask().getRunCount());
        assertEquals(1, databaseAccess.mapper(CollaborationRunLinkMapper.class).selectCount(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise).eq(CollaborationRunLinkRow::getRunId, accepted.runId())));
    }

    @Test
    void concurrentSubmissionOfOneWorkReservesOnlyOneExecution() throws Exception {
        routing.route(enterprise, receive("concurrent-submit", "同一岗位同时提交").getId());
        var lease = dispatch.claim("concurrent-node").orElseThrow();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> dispatch.submit(lease));
            var second = executor.submit(() -> dispatch.submit(lease));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertTrue((a == null) != (b == null));
            assertEquals(1, onlyTask().getRunCount());
            assertEquals(1, reserved());
            assertEquals(1, databaseAccess.mapper(CollaborationRunLinkMapper.class).selectCount(new LambdaQueryWrapper<CollaborationRunLinkRow>()
                .eq(CollaborationRunLinkRow::getTaskId, onlyTask().getId())));
        }
    }

    @Test
    void largeChineseAnswerUsesPrivateFileWithoutLosingContent() throws Exception {
        var incoming = receive("large", "生成完整的考勤明细说明");
        routing.route(enterprise, incoming.getId());
        var accepted = dispatch.submit(dispatch.claim("large-dispatch").orElseThrow());
        String expected = "员工编号 E001，考勤二十天，已完成来源核对。\n".repeat(700);
        allowModelCalls = true;
        modelResponse = exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var delta = Map.of("id", "large-model", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", expected), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(delta) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.runId(), false).orElseThrow().terminal());
        }
        var link = link(accepted.runId());
        var prepared = generated.prepare(link);
        assertNotNull(prepared);
        observer.observe(enterprise, link.getId(), prepared);
        assertEquals("completed", onlyTask().getStatus());
        var answer = databaseAccess.mapper(CollaborationArtifactMapper.class).selectOne(new LambdaQueryWrapper<CollaborationArtifactRow>()
            .eq(CollaborationArtifactRow::getEnterpriseId, enterprise).eq(CollaborationArtifactRow::getProducerRunId, accepted.runId())
            .eq(CollaborationArtifactRow::getKind, "file"));
        assertNotNull(answer);
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var file = fileAccess.ready(actor, answer.getFileId());
        assertEquals("collaboration_artifact", file.purpose());
        assertNull(file.expiresAt(), "已交付成果不能按临时文件到期清理");
        try (var input = storage.open(file)) {
            assertEquals(expected, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private CollaborationParticipantRow role(String id) {
        return databaseAccess.mapper(CollaborationParticipantMapper.class).selectById(id);
    }

    private CollaborationRunLinkRow link(String run) {
        return databaseAccess.mapper(CollaborationRunLinkMapper.class).selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise).eq(CollaborationRunLinkRow::getRunId, run));
    }
}
