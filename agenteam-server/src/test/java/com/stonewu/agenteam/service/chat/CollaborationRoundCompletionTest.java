package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.collaboration.CollaborationRunAccessService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实框架和内部工具，验证正常交接、等待和完成不会被当作模型失败。 */
class CollaborationRoundCompletionTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired private CollaborationRunAccessService runAccess;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"collab_ask_user", "collab_handoff", "collab_submit_result", "collab_complete_task"})
    void committedEndStopsBeforeAnotherModelCallAndFinishesRun(String endTool) throws Exception {
        startTask("核对合成考勤后按指定动作结束本轮");
        var accepted = dispatch.submit(dispatch.claim("round-end-dispatch").orElseThrow());
        var iteration = new AtomicInteger();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            int number = iteration.getAndIncrement();
            String tool = number == 0 ? "collab_publish_artifact" : endTool;
            Map<String, Object> args;
            if (number == 0) {
                args = Map.of("expectedInputRevision", 1, "artifactKey", "round_input", "title", "合成考勤",
                    "summary", "已核对", "kind", "json", "content", Map.of("employeeId", "E001", "days", 20));
            } else if (endTool.equals("collab_ask_user")) {
                args = Map.of("expectedInputRevision", 1, "question", "请确认统计月份", "options", List.of());
            } else if (endTool.equals("collab_handoff")) {
                args = Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(), "completedSummary", "考勤已核对",
                    "nextGoal", "计算合成工资", "acceptanceCriteria", "读取原成果核对数字", "artifactRefs", references(artifact("round_input")),
                    "dependsOnWorkIds", List.of());
            } else if (endTool.equals("collab_complete_task")) {
                args = Map.of("expectedInputRevision", 1, "summary", "已完成合成资料核对", "artifactRefs", references(artifact("round_input")),
                    "acceptedPriorResults", List.of());
            } else {
                args = Map.of("expectedInputRevision", 1, "summary", "已完成合成资料核对", "artifactRefs", references(artifact("round_input")));
            }
            var delta = Map.of("tool_calls", List.of(Map.of("index", 0, "id", "round-call-" + number, "type", "function",
                "function", Map.of("name", modelTool(request, tool), "arguments", json.writeValueAsString(args)))));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var chunk = Map.of("id", "round-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", "tool_calls")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.runId(), false).orElseThrow().terminal());
        }
        var finished = runs.find(enterprise, accepted.runId(), false).orElseThrow();
        assertEquals("completed", finished.status());
        assertNull(finished.errorCode());
        assertEquals(2, iteration.get(), "结束动作提交后不得再调用模型");
        assertTrue(approvals.forRun(enterprise, accepted.runId()).isEmpty());
        observer.observe(enterprise, link(accepted.runId()).getId());
        String expected = endTool.equals("collab_ask_user") ? "waiting_input" : endTool.equals("collab_handoff") ? "active" : "completed";
        assertEquals(expected, onlyTask().getStatus());
    }

    @Test
    void changedWorkWithoutCommittedActionCannotBeReportedAsSuccessful() {
        startTask("检查没有成功动作的执行");
        var active = startNext();
        databaseAccess.mapper(CollaborationWorkItemMapper.class).update(new LambdaUpdateWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationWorkItemRow::getId, link(active.run().id()).getWorkId())
            .set(CollaborationWorkItemRow::getStatus, "waiting_input"));
        assertFalse(runAccess.hasCommittedRoundEnd(active.run()));
    }

    @Test
    void newInputAfterCommittedQuestionStillInvalidatesOldRound() {
        startTask("先等待统计月份");
        var active = startNext();
        invoke(active, "ask-first", "collab_ask_user", Map.of("expectedInputRevision", 1, "question", "请确认月份"));
        assertTrue(runAccess.hasCommittedRoundEnd(active.run()));
        var task = onlyTask();
        inputs.append(identities.actor(enterprise, initiator.id(), binding.getId()), task.getId(), task.getRevision(), "changed-month", "改为九月", null);
        assertEquals("TASK_INPUT_CHANGED", assertThrows(ApiException.class, () -> runAccess.hasCommittedRoundEnd(active.run())).code());
    }
}
