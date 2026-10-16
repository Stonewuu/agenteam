package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationToolDefinitions;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionStepIds;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.collaboration.CollaborationRunObserver;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.collaboration.CollaborationSourceAccess;
import com.stonewu.agenteam.service.collaboration.CollaborationInputService;
import com.stonewu.agenteam.service.file.GeneratedFileService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.mapper.execution.RunApprovalMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import com.stonewu.agenteam.service.workspace.ConversationWorkspaceStore;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 正式插件、工具事务、执行器与两个独立岗位之间的交接及等待恢复测试。 */
class CollaborationHandoffTest extends CollaborationToolTestSupport {
    @Autowired private ConversationWorkspaceStore workspaces;
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @Override
    protected boolean requiresBusinessTool() {
        return true;
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void hrHandsOffInternallyAndFinanceReadsExactArtifactWithoutPlatformMessage() throws Exception {
        startTask("核算考勤和工资");
        requireBusinessApproval();
        var hr = startNext();
        var denied = assertThrows(ApiException.class, () -> invoke(hr, "ordinary-write", "todo_create", Map.of("title", "未经确认不能创建")));
        assertEquals("TOOL_APPROVAL_REQUIRED", denied.code());
        try (var control = new ToolCallControl(() -> {
        }); var scope = workspaces.open(hr.run(), hr.run().conversationId(), control, Duration.ofSeconds(10), () -> {
        })) {
            scope.prepare().write(RuntimeContext.empty(), "work/attendance-verified.csv", "员工编号,实际出勤\nE001,20.5\n");
            scope.commit(Set.of(), Set.of());
        }
        invoke(hr, "publish-attendance", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "attendance",
            "title", "考勤明细", "summary", "本月考勤已核对", "kind", "json", "content", Map.of("employeeId", "E001", "days", 20.5)));
        var attendance = artifact("attendance");
        var financeRole = financeRole();
        var input = Map.<String, Object>of("expectedInputRevision", 1, "targetRoleId", financeRole.getId(), "completedSummary", "已核对本月考勤",
            "nextGoal", "按考勤明细核算工资", "acceptanceCriteria", "员工编号和月份保持一致", "artifactRefs", references(attendance), "dependsOnWorkIds", List.of());
        var handedOff = invoke(hr, "handoff", "collab_handoff", input);
        assertFalse(handedOff.path("isError").asBoolean(), handedOff::toString);
        assertEquals(handedOff.toString(), invoke(hr, "handoff", "collab_handoff", input).toString());
        var task = onlyTask();
        assertEquals(financeRole.getId(), task.getOwnerParticipantId());
        assertEquals(2, task.getWorkCount());
        assertEquals(1, task.getHandoffCount());
        var oldWork = work(link(hr.run().id()).getWorkId());
        assertEquals("handed_off", oldWork.getStatus());
        assertEquals("COLLABORATION_WORK_FINISHED", assertThrows(ApiException.class, () -> lifecycle.beforeExternalStep(hr.lease())).code());
        assertThrows(ApiException.class, () -> invoke(hr, "late-write", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "late",
            "title", "不得写入", "summary", "交接后不得继续修改", "kind", "text", "content", "不得写入")));
        lifecycle.finish(hr.lease(), "failed", "SIMULATED_AFTER_HANDOFF", "模拟交接后原执行失败");
        observer.observe(enterprise, link(hr.run().id()).getId());
        assertEquals("handed_off", work(oldWork.getId()).getStatus());
        var next = startNext();
        assertEquals(task.getOwnerWorkId(), link(next.run().id()).getWorkId());
        assertNotEquals(hr.run().conversationId(), next.run().conversationId());
        assertEquals(hr.run().executionConfig().path("workspaceProjectId"), next.run().executionConfig().path("workspaceProjectId"));
        var sharedFile = invoke(next, "read-shared-file", "read_file", Map.of("path", "work/attendance-verified.csv"));
        assertFalse(sharedFile.path("isError").asBoolean(), sharedFile::toString);
        assertTrue(sharedFile.toString().contains("E001,20.5"), sharedFile::toString);
        var exact = invoke(next, "read", "collab_read_artifact", Map.of("artifactId", attendance.getId(), "version", 1));
        assertTrue(exact.toString().contains("20.5"), exact::toString);
        invoke(next, "complete", "collab_complete_task", Map.of("expectedInputRevision", 1, "summary", "工资核算已完成",
            "artifactRefs", references(attendance), "acceptedPriorResults", List.of()));
        assertEquals("completed", onlyTask().getStatus());
        assertNotNull(onlyTask().getFinalArtifactId());
        lifecycle.finish(next.lease(), "completed", null, null);
        observer.observe(enterprise, link(next.run().id()).getId());
        assertNull(work(task.getOwnerWorkId()).getActiveRunId());
    }

    @Test
    void rolesShareOnlyTheirOwnTaskProjectAndRepeatedRoutingReusesIt() {
        var first = receive("shared-project-first", "第一个合成任务");
        routing.route(enterprise, first.getId());
        var firstTask = onlyTask();
        var firstRoles = databaseAccess.mapper(CollaborationParticipantMapper.class).selectList(
            new LambdaQueryWrapper<CollaborationParticipantRow>().eq(CollaborationParticipantRow::getEnterpriseId, enterprise)
                .eq(CollaborationParticipantRow::getTaskId, firstTask.getId()));
        assertEquals(2, firstRoles.size());
        String firstProject = firstRoles.getFirst().getProjectId();
        assertNotNull(firstProject);
        assertTrue(firstRoles.stream().allMatch(role -> firstProject.equals(role.getProjectId())));
        routing.route(enterprise, first.getId());
        assertEquals(firstTask.getId(), onlyTask().getId());
        startTask("第二个合成任务");
        var secondRoles = databaseAccess.mapper(CollaborationParticipantMapper.class).selectList(
            new LambdaQueryWrapper<CollaborationParticipantRow>().eq(CollaborationParticipantRow::getEnterpriseId, enterprise)
                .ne(CollaborationParticipantRow::getTaskId, firstTask.getId()));
        assertEquals(2, secondRoles.size());
        String secondProject = secondRoles.getFirst().getProjectId();
        assertNotNull(secondProject);
        assertNotEquals(firstProject, secondProject);
        assertTrue(secondRoles.stream().allMatch(role -> secondProject.equals(role.getProjectId())));
    }

    @Test
    void waitingReleasesRunAndResumesSameHrConversationAfterFinanceFinishes() {
        startTask("人事统筹，财务协助后合并");
        var hr = startNext();
        invoke(hr, "assign", "collab_assign", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(), "goal", "整理工资基础数据",
            "acceptanceCriteria", "列出员工编号和工资", "artifactRefs", List.of(), "dependsOnWorkIds", List.of(), "required", true));
        var assistance = databaseAccess.mapper(CollaborationWorkItemMapper.class).selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationWorkItemRow::getTaskId, onlyTask().getId())
            .eq(CollaborationWorkItemRow::getPurpose, "assist"));
        invoke(hr, "wait", "collab_wait", Map.of("expectedInputRevision", 1, "workItemIds", List.of(assistance.getId()), "reason", "等待工资资料后合并"));
        lifecycle.finish(hr.lease(), "completed", null, null);
        observer.observe(enterprise, link(hr.run().id()).getId());
        var waiting = work(onlyTask().getOwnerWorkId());
        assertEquals("waiting_dependency", waiting.getStatus());
        assertNull(waiting.getActiveRunId());
        var fin = startNext();
        invoke(fin, "submit", "collab_submit_result", Map.of("expectedInputRevision", 1, "summary", "工资资料已整理", "artifactRefs", List.of()));
        assertNotEquals("completed", onlyTask().getStatus(), "协助岗位不能结束总任务");
        lifecycle.finish(fin.lease(), "completed", null, null);
        observer.observe(enterprise, link(fin.run().id()).getId());
        assertEquals("ready", work(waiting.getId()).getStatus());
        var resumed = startNext();
        assertEquals(hr.run().conversationId(), resumed.run().conversationId());
        assertEquals(2, link(resumed.run().id()).getRunRound());
        assertEquals(waiting.getId(), link(resumed.run().id()).getWorkId());
        invoke(resumed, "finish", "collab_complete_task", Map.of("expectedInputRevision", 1, "summary", "已合并考勤和工资", "artifactRefs", List.of(), "acceptedPriorResults", List.of()));
        assertEquals("completed", onlyTask().getStatus());
        lifecycle.finish(resumed.lease(), "completed", null, null);
    }

    @Test
    void actualAgentScopeModelCanPublishInternalArtifactWithoutAnApproval() throws Exception {
        startTask("核对并保存考勤成果");
        var accepted = dispatch.submit(dispatch.claim("model-internal-dispatch").orElseThrow());
        var iteration = new AtomicInteger();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            Map<String, Object> delta;
            String finish;
            if (iteration.getAndIncrement() == 0) {
                String tool = modelTool(request, "collab_publish_artifact");
                var args = Map.of("expectedInputRevision", 1, "artifactKey", "model_attendance", "title", "考勤明细", "summary", "已核对测试员工",
                    "kind", "json", "content", Map.of("employeeId", "E001", "days", 20));
                delta = Map.of("tool_calls", List.of(Map.of("index", 0, "id", "internal-model-call", "type", "function",
                    "function", Map.of("name", tool, "arguments", json.writeValueAsString(args)))));
                finish = "tool_calls";
            } else {
                delta = Map.of("content", "考勤成果已经保存。");
                finish = "stop";
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var chunk = Map.of("id", "internal-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.runId(), false).orElseThrow().terminal()
                || runs.find(enterprise, accepted.runId(), false).orElseThrow().status().equals("waiting_approval"));
        }
        assertEquals("completed", runs.find(enterprise, accepted.runId(), false).orElseThrow().status());
        assertTrue(approvals.forRun(enterprise, accepted.runId()).isEmpty());
        assertNotNull(artifact("model_attendance"));
        observer.observe(enterprise, link(accepted.runId()).getId());
        assertEquals("completed", onlyTask().getStatus());
    }

    @Test
    void newRequirementsWakeTheOwnerForPlanningWithoutRunningOldDependencies() {
        startTask("人事统筹本月工资核算");
        var hr = startNext();
        invoke(hr, "old-assign", "collab_assign", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(), "goal", "统计一月工资",
            "acceptanceCriteria", "列出一月工资", "artifactRefs", List.of(), "dependsOnWorkIds", List.of(), "required", true));
        var old = databaseAccess.mapper(CollaborationWorkItemMapper.class).selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationWorkItemRow::getTaskId, onlyTask().getId())
            .eq(CollaborationWorkItemRow::getPurpose, "assist"));
        invoke(hr, "old-wait", "collab_wait", Map.of("expectedInputRevision", 1, "workItemIds", List.of(old.getId()), "reason", "等待一月工资"));
        lifecycle.finish(hr.lease(), "completed", null, null);
        observer.observe(enterprise, link(hr.run().id()).getId());
        var task = onlyTask();
        inputs.append(identities.actor(enterprise, initiator.id(), binding.getId()), task.getId(), task.getRevision(), "new-month", "改成二月工资", null);
        assertEquals("blocked", work(old.getId()).getStatus());
        assertEquals(1L, work(old.getId()).getInputRevision());
        var planning = startNext();
        assertEquals(task.getOwnerWorkId(), link(planning.run().id()).getWorkId());
        assertEquals(hr.run().conversationId(), planning.run().conversationId());
        assertTrue(planning.run().executionConfig().at("/collaboration/planningOnly").asBoolean());
        assertTrue(catalog.list(planning.run()).values().stream().noneMatch(tool -> tool.definition().name().equals("todo_create")));
        var assigned = invoke(planning, "replace-month", "collab_assign", Map.of("expectedInputRevision", 2, "targetRoleId", financeRole().getId(), "goal", "统计二月工资",
            "acceptanceCriteria", "列出二月工资", "artifactRefs", List.of(), "dependsOnWorkIds", List.of(), "required", false, "replacesWorkItemId", old.getId()));
        assertFalse(assigned.path("isError").asBoolean(), assigned::toString);
        assertEquals("superseded", work(old.getId()).getStatus());
        var replacement = work(old.getId()).getReplacementWorkId();
        assertTrue(work(replacement).getRequired());
        invoke(planning, "new-wait", "collab_wait", Map.of("expectedInputRevision", 2, "workItemIds", List.of(replacement), "reason", "等待二月工资"));
        lifecycle.finish(planning.lease(), "completed", null, null);
        observer.observe(enterprise, link(planning.run().id()).getId());
        var fin = startNext();
        assertEquals(replacement, link(fin.run().id()).getWorkId());
        invoke(fin, "new-submit", "collab_submit_result", Map.of("expectedInputRevision", 2, "summary", "二月工资已统计", "artifactRefs", List.of()));
        lifecycle.finish(fin.lease(), "completed", null, null);
        observer.observe(enterprise, link(fin.run().id()).getId());
        var resumed = startNext();
        assertFalse(resumed.run().executionConfig().at("/collaboration/planningOnly").asBoolean());
        assertEquals(hr.run().conversationId(), resumed.run().conversationId());
        invoke(resumed, "new-complete", "collab_complete_task", Map.of("expectedInputRevision", 2, "summary", "二月工资核算完成", "artifactRefs", List.of(), "acceptedPriorResults", List.of()));
        assertEquals("completed", onlyTask().getStatus());
    }

    @Test
    void onlyExplicitFileIsSharedAndProducerBotCanStopWithoutLosingSavedFile() throws Exception {
        startTask("按精确考勤文件核算工资");
        var hr = startNext();
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var prepared = generated.collaborationText(actor, agent, version, hr.run().id(), "E001,20.5\nE002,18.75\n");
        var file = generated.register(prepared);
        var unreferenced = generated.register(generated.collaborationText(actor, agent, version, hr.run().id(), "本轮未选择的其他资料"));
        invoke(hr, "publish-file", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "exact_attendance", "title", "精确考勤",
            "summary", "包含两个测试员工的精确天数", "kind", "file", "fileId", file.id()));
        var artifact = artifact("exact_attendance");
        invoke(hr, "file-handoff", "collab_handoff", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(),
            "completedSummary", "考勤文件已核对", "nextGoal", "按文件的精确天数核算", "acceptanceCriteria", "不得从摘要估算",
            "artifactRefs", references(artifact), "dependsOnWorkIds", List.of()));
        lifecycle.finish(hr.lease(), "completed", null, null);
        observer.observe(enterprise, link(hr.run().id()).getId());
        var next = startNext();
        assertEquals(1, next.run().executionConfig().path("inputSourceFiles").size());
        assertEquals(file.id(), next.run().executionConfig().at("/inputSourceFiles/0/id").asText());
        assertEquals(file.sha256(), next.run().executionConfig().at("/inputSourceFiles/0/sha256").asText());
        sourceAccess.requireCurrent(actor, next.run(), file);
        assertThrows(ApiException.class, () -> sourceAccess.requireCurrent(actor, next.run(), unreferenced));
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId())
            .eq(ChatBotBindingRow::getEnterpriseId, enterprise).set(ChatBotBindingRow::getStatus, "disabled"));
        assertEquals(file.id(), fileAccess.ready(actor, file.id()).id());
        assertEquals(initiator.id(), executionAccess.actor(next.run()).userId());
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getId, group.getId()).set(ChatConversationRow::getResultPolicy, "full_allowed"));
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getId, onlyTask().getId()).set(CollaborationTaskRow::getOutputVisibility, "chat_full"));
        var outgoing = outbound.file(enterprise, group.getId(), finance.getId(), onlyTask().getId(), null, "finance-public-file", artifact);
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).ne(ChatOutboundMessageRow::getId, outgoing.getId())
            .set(ChatOutboundMessageRow::getStatus, "cancelled"));
        var lease = sending.claim("file-sender").orElseThrow();
        assertEquals(finance.getConnectionId(), sending.prepare(lease).application().id());
        var upload = sending.uploadFile(lease);
        try (var content = upload.open().get()) {
            assertEquals("E001,20.5\nE002,18.75\n", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getId, outgoing.getId()).set(ChatOutboundMessageRow::getStatus, "cancelled"));
        databaseAccess.mapper(FileSqlMapper.class).update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getId, file.id())
            .set(FileObjectRow::getDeletedAt, Instant.now()));
        assertThrows(ApiException.class, () -> sourceAccess.requireCurrent(actor, next.run(), file));
    }

}
