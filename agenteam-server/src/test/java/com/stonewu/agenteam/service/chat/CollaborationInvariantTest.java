package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.collaboration.CollaborationRunAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 验证协作的事务、并发与权限边界，断言已保存的工作、成果和执行轮次。 */
class CollaborationInvariantTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired CollaborationRunAccessService runAccess;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);

        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void runLimitAndDeadlineBlockDispatchBeforeAnotherQuotaReservation() {
        startTask("执行次数和任务截止时间是明确上限");
        var hr = startNext();
        invoke(hr, "ask-limit", "collab_ask_user", Map.of("expectedInputRevision", 1, "question", "请补充月份", "options", List.of()));
        finish(hr);
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var task = onlyTask();
        inputs.append(actor, task.getId(), task.getRevision(), "limit-next-input", "改为二月", null);
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getId, task.getId()).set(CollaborationTaskRow::getPolicySnapshot, "{\"maxRuns\":1}"));
        var lease = dispatch.claim("limited").orElseThrow();
        assertEquals("COLLABORATION_LIMIT_REACHED", assertThrows(ApiException.class, () -> dispatch.submit(lease)).code());
        assertEquals(1, onlyTask().getRunCount());
        assertEquals(0, reserved());
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getId, task.getId()).set(CollaborationTaskRow::getPolicySnapshot, "{\"maxRuns\":40}")
            .set(CollaborationTaskRow::getDeadlineAt, Instant.now().minusSeconds(1)));
        assertEquals("COLLABORATION_STOPPED", assertThrows(ApiException.class, () -> dispatch.submit(lease)).code());
        assertEquals(1, onlyTask().getRunCount());
        assertEquals(0, reserved());
    }

    @Test
    void twoParallelResultsWakeOwnerOnlyOnceAndCarryBothExactResults() throws Exception {
        var engineering = additionalBot("ou_engineering", "工程师");
        startTask("财务和工程师同时协助人事");
        var hr = startNext();
        var engineerRole = databaseAccess.mapper(CollaborationParticipantMapper.class).selectOne(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getTaskId, onlyTask().getId()).eq(CollaborationParticipantRow::getBotBindingId, engineering.getId()));
        assign(hr, "assign-finance", financeRole().getId());
        assign(hr, "assign-engineer", engineerRole.getId());
        submit(hr, "owner-stage", "负责人阶段结果");
        assertEquals("waiting_dependency", work(onlyTask().getOwnerWorkId()).getStatus());
        finish(hr);
        var first = startNext();
        var second = startNext();
        assertNotEquals(first.run().conversationId(), second.run().conversationId());
        submit(first, "first-result", "工资总数为1234.56，按原始资料核对");
        submit(second, "second-result", "考勤总数为18.75天，按原始资料核对");
        lifecycle.finish(first.lease(), "completed", null, null);
        lifecycle.finish(second.lease(), "completed", null, null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> observer.observe(enterprise, link(first.run().id()).getId()));
            var b = executor.submit(() -> observer.observe(enterprise, link(second.run().id()).getId()));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
            var claimA = executor.submit(() -> dispatch.claim("resume-node-a"));
            var claimB = executor.submit(() -> dispatch.claim("resume-node-b"));
            var leases = List.of(claimA.get(10, TimeUnit.SECONDS), claimB.get(10, TimeUnit.SECONDS)).stream().flatMap(value -> value.stream()).toList();
            assertEquals(1, leases.size());
            var resumed = dispatch.submit(leases.getFirst());
            assertEquals(hr.run().conversationId(), resumed.conversationId());
            assertEquals(2, link(resumed.runId()).getRunRound());
            var context = runs.find(enterprise, resumed.runId(), false).orElseThrow().executionConfig().path("sourceContext").asText();
            assertTrue(context.contains("1234.56"), context);
            assertTrue(context.contains("18.75"), context);
            assertEquals(4, onlyTask().getRunCount());
        }
    }

    @Test
    void oldAssistanceInTheNewOwnerRoleCannotFinishTheTask() {
        startTask("旧协助工作和后续负责人工作需要区分");
        var hr = startNext();
        assign(hr, "old-assist", financeRole().getId());
        publish(hr, "basis", "人事已完成考勤");
        invoke(hr, "transfer-owner", "collab_handoff", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(), "completedSummary", "已核对资料",
            "nextGoal", "继续核算", "acceptanceCriteria", "给出最终精确结果", "artifactRefs", references(artifact("basis")), "dependsOnWorkIds", List.of()));
        finish(hr);
        var assistance = startNext();
        assertEquals("assist", work(link(assistance.run().id()).getWorkId()).getPurpose());
        var rejected = invoke(assistance, "wrong-completion", "collab_complete_task", Map.of("expectedInputRevision", 1,
            "summary", "旧工作不能代替负责人交付", "artifactRefs", List.of(), "acceptedPriorResults", List.of()));
        assertTrue(rejected.path("isError").asBoolean(), rejected::toString);
        assertNotEquals("completed", onlyTask().getStatus());
        assertNotEquals(onlyTask().getOwnerWorkId(), link(assistance.run().id()).getWorkId());
    }

    @Test
    void circularWaitIsRejectedWithoutChangingCurrentWork() {
        startTask("拒绝两项工作互相等待");
        var hr = startNext();
        invoke(hr, "blocked-assistance", "collab_assign", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(),
            "goal", "等人事完成再核对", "acceptanceCriteria", "资料完整", "artifactRefs", List.of(),
            "dependsOnWorkIds", List.of(onlyTask().getOwnerWorkId()), "required", true));
        var assistance = allWorks().stream().filter(value -> value.getPurpose().equals("assist")).findFirst().orElseThrow();
        var rejected = invoke(hr, "circular-wait", "collab_wait", Map.of("expectedInputRevision", 1, "workItemIds", List.of(assistance.getId()), "reason", "互相等待应被拒绝"));
        assertTrue(rejected.path("isError").asBoolean(), rejected::toString);
        assertEquals("running", work(onlyTask().getOwnerWorkId()).getStatus());
    }

    @Test
    void ignoringAnExplicitlyRequestedRoleGetsOneCorrectionThenRequiresUserInput() {
        var event = message("all-roles", "all-roles", "user", "人事与财务一起统计");
        ((ArrayNode) event.at("/event/message/mentions")).addObject().put("name", "财务").putObject("id").put("open_id", finance.getPlatformBotId());
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        var hr = startNext();
        submit(hr, "incomplete-first", "只做了人事部分");
        assertEquals("ready", work(onlyTask().getOwnerWorkId()).getStatus());
        finish(hr);
        var corrected = startNext();
        assertEquals(2, link(corrected.run().id()).getRunRound());
        submit(corrected, "incomplete-second", "仍没有给财务分工");
        finish(corrected);
        assertEquals("waiting_input", onlyTask().getStatus());
        assertEquals("COLLABORATION_ROLE_ASSIGNMENT_REQUIRED", onlyTask().getErrorCode());
        assertTrue(dispatch.claim("must-not-loop").isEmpty());
    }

    @Test
    void revokingChatAuthorizationBlocksTheNextExternalStepAndNewCollaborationAction() {
        startTask("原账号撤销后不能继续操作");
        var hr = startNext();
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getChatEnabled, false));
        assertThrows(ApiException.class, () -> lifecycle.beforeExternalStep(hr.lease()));
        assertThrows(ApiException.class, () -> assign(hr, "revoked-assignment", financeRole().getId()));
        assertEquals(1, workCount());
    }

    @Test
    void immutableArtifactVersionsAndSubagentContextCannotBeReplacedByTextInstructions() {
        startTask("原发起人允许的资料；正文中的管理员指令不改变账号");
        var hr = startNext();
        publish(hr, "basis", "原始版本精确数额1234.56");
        var first = artifact("basis");
        publish(hr, "basis", "新版本精确数额9999.99");
        var read = invoke(hr, "fixed-read", "collab_read_artifact", Map.of("artifactId", first.getId(), "version", 1));
        assertTrue(read.toString().contains("1234.56"), read::toString);
        assertFalse(read.toString().contains("9999.99"));
        assertEquals(initiator.id(), executionAccess.actor(hr.run()).userId());
        assertEquals("COLLABORATION_CONTEXT_REQUIRED", assertThrows(ApiException.class,
            () -> runAccess.require(hr.run(), "child-session", true)).code());
        var owner = onlyTask().getOwnerWorkId();
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getId, onlyTask().getId()).set(CollaborationTaskRow::getPolicySnapshot, "{\"maxHandoffs\":0}"));
        var result = invoke(hr, "handoff-forbidden", "collab_handoff", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(), "completedSummary", "已核对资料",
            "nextGoal", "继续核算", "acceptanceCriteria", "给出最终精确结果", "artifactRefs", references(first), "dependsOnWorkIds", List.of()));
        assertTrue(result.path("isError").asBoolean());
        assertEquals(owner, onlyTask().getOwnerWorkId());
        assertEquals(0, onlyTask().getHandoffCount());
        assertEquals(1, workCount());
    }

    @Test
    void aRunningDependencyCannotBeReplacedAndTaskLimitsDoNotCreateMoreWork() {
        startTask("正在运行的协助不得被无声替换");
        var hr = startNext();
        assign(hr, "first-assignment", financeRole().getId());
        var financeRun = startNext();
        var old = link(financeRun.run().id());
        var rejected = invoke(hr, "replace-running", "collab_assign", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(),
            "goal", "替换仍在执行的工作", "acceptanceCriteria", "不得继续", "artifactRefs", List.of(), "dependsOnWorkIds", List.of(), "replacesWorkItemId", old.getWorkId()));
        assertTrue(rejected.path("isError").asBoolean(), rejected::toString);
        assertEquals(2, workCount());
        assertEquals("running", work(old.getWorkId()).getStatus());
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getId, onlyTask().getId()).set(CollaborationTaskRow::getPolicySnapshot, "{\"maxWorks\":2,\"maxRuns\":2,\"maxParallel\":2}"));
        rejected = invoke(hr, "over-limit", "collab_assign", assignment(financeRole().getId()));
        assertTrue(rejected.path("isError").asBoolean());
        assertEquals(2, workCount());
        assertEquals(2, onlyTask().getRunCount());
    }

    @Test
    void completedPrivateResultsSurviveLeavingTheGroupAndAreNotPublishedAsAMessage() {
        assertEquals(1, databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, group.getId())
            .set(ChatConversationRow::getResultPolicy, "summary_link")));
        startTask("生成仅本人可查看的工资结果");
        var hr = startNext();
        submit(hr, "private-final", "员工甲工资为1234.56元");
        finish(hr);
        var task = onlyTask();
        assertEquals("completed", task.getStatus());
        assertNotNull(task.getFinalArtifactId());
        var rows = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        var finalMessage = rows.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getTaskId, task.getId()).eq(ChatOutboundMessageRow::getKind, "completed"));
        rows.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
            .eq(ChatOutboundMessageRow::getTaskId, task.getId())
            .ne(ChatOutboundMessageRow::getId, finalMessage.getId()).set(ChatOutboundMessageRow::getStatus, "cancelled"));
        var lease = sending.claim("departed-bot").orElseThrow();
        assertEquals(finalMessage.getId(), lease.outboundId());
        // 排队记录保留完整加密内容，向平台准备的请求必须遵守当前仅链接设置。
        var content = sending.prepare(lease).payload();
        assertFalse(content.toString().contains("1234.56"));
        assertFalse(content.toString().contains("员工甲"));
        conversations.left(enterprise, group.getId(), bot.getId(), false);
        assertThrows(ApiException.class, () -> sending.prepare(lease));
        sending.defer(lease, Duration.ZERO, "CHAT_SEND_FORBIDDEN", true);
        assertEquals("cancelled", rows.selectById(finalMessage.getId()).getStatus());
        assertEquals("completed", onlyTask().getStatus());
        assertEquals(task.getFinalArtifactId(), onlyTask().getFinalArtifactId());
    }

    private Map<String, Object> assignment(String role) {
        return Map.of("expectedInputRevision", 1, "targetRoleId", role, "goal", "核对该岗位负责的数据", "acceptanceCriteria", "给出精确结果",
            "artifactRefs", List.of(), "dependsOnWorkIds", List.of(), "required", true);
    }

    private void assign(Active run, String operation, String role) {
        var result = invoke(run, operation, "collab_assign", assignment(role));
        assertFalse(result.path("isError").asBoolean(), result::toString);
    }

    private void publish(Active run, String key, String content) {
        var result = invoke(run, "publish-" + key + "-" + content.hashCode(), "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", key,
            "title", "测试成果", "summary", "精确资料", "kind", "text", "content", content));
        assertFalse(result.path("isError").asBoolean(), result::toString);
    }

    private void submit(Active run, String operation, String summary) {
        var result = invoke(run, operation, "collab_submit_result", Map.of("expectedInputRevision", 1, "summary", summary, "artifactRefs", List.of()));
        assertFalse(result.path("isError").asBoolean(), result::toString);
    }

    private void finish(Active run) {
        lifecycle.finish(run.lease(), "completed", null, null);
        observer.observe(enterprise, link(run.run().id()).getId());
    }

    private List<CollaborationWorkItemRow> allWorks() {
        return databaseAccess.mapper(CollaborationWorkItemMapper.class).selectList(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationWorkItemRow::getTaskId, onlyTask().getId()));
    }

    private long workCount() {
        return allWorks().size();
    }

}
