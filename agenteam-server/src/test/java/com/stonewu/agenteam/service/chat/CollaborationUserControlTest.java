package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.request.ApprovalDecisionRequest;
import com.stonewu.agenteam.service.collaboration.CollaborationWorker;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证原发起人的操作确认、补充要求和取消，以及取消与模型交接或完成同时发生的结果。 */
class CollaborationUserControlTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired RunApprovalService approvalService;
    @Autowired CollaborationWorker collaborationWorker;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
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
        ENV.close();
    }

    @Test
    void changingRequirementsInvalidatesThePendingBusinessApproval() throws Exception {
        var run = waiting();
        var confirmation = approvals.forRun(enterprise, run.id()).getFirst();
        assertEquals(initiator.id(), confirmation.approverUserId());
        assertEquals("pending", confirmation.status());
        mvc.perform(get(base() + "/runs/" + run.id() + "/approvals").cookie(cookie)).andExpect(status().is4xxClientError());
        assertEquals("pending", approvals.find(enterprise, confirmation.id(), false).orElseThrow().status());
        assertEquals(0, count("todo_item"));
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var task = onlyTask();
        inputs.append(actor, task.getId(), task.getRevision(), "new-requirements", "改为统计二月，原来的待办不要创建", null);
        collaborationWorker.recover();
        assertEquals("cancelled", runs.find(enterprise, run.id(), false).orElseThrow().status());
        assertNotEquals("pending", approvals.find(enterprise, confirmation.id(), false).orElseThrow().status());
        assertThrows(ApiException.class, () -> approvalService.decide(actor, confirmation.id(), confirmation.revision(),
            new ApprovalDecisionRequest("approve", confirmation.requestHash(), null)));
        assertEquals(0, count("todo_item"));
        var next = dispatch.submit(dispatch.claim("approval-new-round").orElseThrow());
        assertEquals(run.conversationId(), next.conversationId());
        assertEquals(2, link(next.runId()).getRunRound());
        assertEquals(2, link(next.runId()).getInputRevision());
    }

    private RunRecord waiting() throws Exception {
        startTask("统计考勤后创建需要原发起人确认的待办");
        requireBusinessApproval();
        var accepted = dispatch.submit(dispatch.claim("approval-fixture").orElseThrow());
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            var args = Map.of("title", "统计一月考勤的合成测试待办");
            var delta = Map.of("tool_calls", List.of(Map.of("index", 0, "id", "approval-model-call", "type", "function",
                "function", Map.of("name", modelTool(request, "todo_create"), "arguments", json.writeValueAsString(args)))));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var chunk = Map.of("id", "approval-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", "tool_calls")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.runId(), false).orElseThrow().terminal()
                || runs.find(enterprise, accepted.runId(), false).orElseThrow().status().equals("waiting_approval"));
        }
        var run = runs.find(enterprise, accepted.runId(), false).orElseThrow();
        assertEquals("waiting_approval", run.status());
        observer.observe(enterprise, link(run.id()).getId());
        return run;
    }

    @Test
    void cancellationAndHandoffSerializeByTaskVersionAndStopSubsequentDispatch() throws Exception {
        startTask("取消与交接竞争");
        var hr = startNext();
        invoke(hr, "race-basis", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "basis", "title", "考勤",
            "summary", "已核对考勤", "kind", "text", "content", "测试员工出勤20天"));
        var task = onlyTask();
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var target = financeRole().getId();
        var refs = references(artifact("basis"));
        var conflict = new AtomicReference<ApiException>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancel = executor.submit(() -> {
                try {
                    inputs.cancel(actor, task.getId(), task.getRevision(), "race-cancel", "原发起人停止");
                } catch (ApiException changed) {
                    conflict.set(changed);
                }
            });
            var handoff = executor.submit(() -> {
                try {
                    invoke(hr, "race-handoff", "collab_handoff", Map.of("expectedInputRevision", 1, "targetRoleId", target,
                        "completedSummary", "已核对考勤", "nextGoal", "核算工资", "acceptanceCriteria", "给出最终结果", "artifactRefs", refs, "dependsOnWorkIds", List.of()));
                } catch (ApiException stopped) {
                    assertEquals(409, stopped.getStatusCode().value());
                } catch (ExecutionStoppedException stopped) {
                    // 原执行已按用户取消请求停止，不能继续交接。
                }
            });
            cancel.get(15, TimeUnit.SECONDS);
            handoff.get(15, TimeUnit.SECONDS);
        }
        if (conflict.get() != null) {
            assertEquals(409, conflict.get().getStatusCode().value());
            inputs.cancel(actor, task.getId(), onlyTask().getRevision(), "cancel-current-version", "读取最新任务后确认停止");
        }
        collaborationWorker.recover();
        lifecycle.finish(hr.lease(), "completed", null, null);
        collaborationWorker.recover();
        // 上一轮先处理取消、再记录执行已结束；下一轮才完成总任务的取消收尾。
        collaborationWorker.recover();
        assertEquals("cancelled", onlyTask().getStatus());
        assertTrue(dispatch.claim("after-cancellation").isEmpty());
        assertTrue(databaseAccess.mapper(CollaborationWorkItemMapper.class).selectList(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getTaskId, task.getId())).stream().noneMatch(work -> Set.of("ready", "running").contains(work.getStatus())));
    }

    @Test
    void cancellationAndCompletionCannotBothCommitAConflictingOutcome() throws Exception {
        startTask("用户取消与负责人完成同时发生");
        var hr = startNext();
        var task = onlyTask();
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var cancelConflict = new AtomicReference<ApiException>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancel = executor.submit(() -> {
                try {
                    inputs.cancel(actor, task.getId(), task.getRevision(), "complete-race-cancel", "停止后续工作");
                } catch (ApiException changed) {
                    cancelConflict.set(changed);
                }
            });
            var complete = executor.submit(() -> {
                try {
                    invoke(hr, "complete-race-finish", "collab_complete_task", Map.of("expectedInputRevision", 1,
                        "summary", "合成结果已完成", "artifactRefs", List.of(), "acceptedPriorResults", List.of()));
                } catch (ApiException stopped) {
                    assertEquals(409, stopped.getStatusCode().value());
                } catch (ExecutionStoppedException stopped) {
                    // 用户取消已经生效，旧工作不能再完成总任务。
                }
            });
            cancel.get(15, TimeUnit.SECONDS);
            complete.get(15, TimeUnit.SECONDS);
        }
        if (cancelConflict.get() != null) {
            assertEquals(409, cancelConflict.get().getStatusCode().value());
            assertEquals("completed", onlyTask().getStatus());
            assertNotNull(onlyTask().getFinalArtifactId());
        } else {
            assertEquals("cancelling", onlyTask().getStatus());
            assertNull(onlyTask().getFinalArtifactId());
        }
        lifecycle.finish(hr.lease(), "completed", null, null);
        collaborationWorker.recover();
        collaborationWorker.recover();
        assertEquals(cancelConflict.get() == null ? "cancelled" : "completed", onlyTask().getStatus());
        assertEquals(1, onlyTask().getRunCount());
        assertTrue(dispatch.claim("after-completion-race").isEmpty());
    }
}
