package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.collaboration.CollaborationInputService;
import com.stonewu.agenteam.service.collaboration.CollaborationRetryService;
import com.stonewu.agenteam.service.collaboration.CollaborationWorker;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证用户在执行中取消、修改要求、重试和控制成果公开范围。 */
class CollaborationControlTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private CollaborationDispatchService dispatch;
    @Autowired private CollaborationWorker worker;
    @Autowired private CollaborationInputService inputs;
    @Autowired private CollaborationRetryService retries;
    @Autowired private ChatIdentityService identities;
    @Autowired private ExecutionAccessService executionAccess;
    @Autowired private RunMapper runs;

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
    void stopRuns() {
        for (var task : databaseAccess.mapper(CollaborationTaskMapper.class).selectList(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).in(CollaborationTaskRow::getStatus, "active", "blocked", "waiting_input", "waiting_approval"))) {
            inputs.cancel(actor(), task.getId(), task.getRevision(), "test-cleanup:" + task.getId(), "结束当前隔离测试");
        }
        lifecycle.stopForUser(enterprise, initiator.id());
        worker.recover();
    }

    @Test
    void cancellingQueuedRunStopsExecutionAndFinishesTaskWithoutCallingModel() {
        startTask();
        var accepted = dispatch.submit(dispatch.claim("cancel-test").orElseThrow());
        var task = onlyTask();
        inputs.cancel(actor(), task.getId(), task.getRevision(), "cancel-user", "不再需要");
        assertEquals("cancelling", onlyTask().getStatus());
        worker.recover();
        worker.recover();
        assertEquals("cancelled", runs.find(enterprise, accepted.runId(), false).orElseThrow().status());
        assertEquals("cancelled", onlyTask().getStatus());
        assertEquals("cancelled", work(task.getOwnerWorkId()).getStatus());
        assertTrue(dispatch.claim("after-cancel").isEmpty());
    }

    @Test
    void newRequirementStopsOldRunAndResumesSameWorkWithNewContext() {
        startTask();
        var first = dispatch.submit(dispatch.claim("input-test").orElseThrow());
        var old = runs.find(enterprise, first.runId(), false).orElseThrow();
        var task = onlyTask();
        inputs.append(actor(), task.getId(), task.getRevision(), "input-user", "只处理上个月数据", null);
        assertEquals("TASK_INPUT_CHANGED", assertThrows(ApiException.class, () -> executionAccess.actor(old)).code());
        worker.recover();
        assertEquals("cancelled", runs.find(enterprise, first.runId(), false).orElseThrow().status());
        var second = dispatch.submit(dispatch.claim("input-next").orElseThrow());
        assertEquals(first.conversationId(), second.conversationId());
        var next = runs.find(enterprise, second.runId(), false).orElseThrow();
        assertEquals(2, next.executionConfig().at("/collaboration/inputRevision").asInt());
        assertEquals(2, next.executionConfig().at("/collaboration/runRound").asInt());
        assertEquals(2, onlyTask().getRunCount());
    }

    @Test
    void unknownBusinessResultCannotBeRetriedOrReactivatedByNewInput() {
        startTask();
        var accepted = dispatch.submit(dispatch.claim("unknown-test").orElseThrow());
        var lease = lifecycle.claim("unknown-run").orElseThrow();
        lifecycle.start(lease).orElseThrow();
        lifecycle.finish(lease, "failed", "TOOL_RESULT_UNKNOWN", "模拟已提交但结果未知");
        worker.recover();
        var task = onlyTask();
        assertEquals("blocked", work(task.getOwnerWorkId()).getStatus());
        assertEquals("TOOL_RESULT_UNKNOWN", assertThrows(ApiException.class,
            () -> retries.retry(actor(), task.getId(), task.getOwnerWorkId(), task.getRevision(), "retry-unknown")).code());
        inputs.append(actor(), task.getId(), task.getRevision(), "input-after-unknown", "补充说明", null);
        assertEquals("blocked", work(task.getOwnerWorkId()).getStatus());
        assertTrue(dispatch.claim("still-unknown").isEmpty());
        assertEquals(1, onlyTask().getRunCount());
        assertEquals("failed", runs.find(enterprise, accepted.runId(), false).orElseThrow().status());
        var current = onlyTask();
        inputs.cancel(actor(), current.getId(), current.getRevision(), "cancel-unknown-result", "停止后续工作但保留核实事项");
        worker.recover();
        worker.recover();
        assertEquals("cancelled", onlyTask().getStatus());
        assertEquals("TOOL_RESULT_UNKNOWN", onlyTask().getErrorCode());
        assertTrue(dispatch.claim("after-unknown-cancel").isEmpty());
        assertEquals(1, onlyTask().getRunCount());
    }

    @Test
    void httpOwnerCanReadAndAppendButAdministratorCannotReadPrivateTask() throws Exception {
        startTask();
        var task = onlyTask();
        String path = base() + "/collaboration/tasks/" + task.getId();
        mvc.perform(get(path).cookie(cookie)).andExpect(status().isNotFound());
        var browser = login();
        var detail = data(mvc.perform(get(path).cookie(browser.cookie())).andExpect(status().isOk()).andReturn());
        assertEquals(task.getShortCode(), detail.at("/task/shortCode").asText());
        assertFalse(detail.toString().contains("payloadEncrypted"));
        assertFalse(detail.toString().contains("senderIdentities"));
        String key = UUID.randomUUID().toString();
        var body = Map.of("requestId", key, "expectedRevision", task.getRevision(), "text", "补充月份要求", "messageReferences", List.of(), "waivedRoleIds", List.of());
        var first = data(writeAs(browser, path + "/inputs", body, key).andExpect(status().isOk()).andReturn());
        assertEquals(first, data(writeAs(browser, path + "/inputs", body, key).andExpect(status().isOk()).andReturn()));
        assertEquals(2L, onlyTask().getInputRevision());
        assertEquals(1, data(mvc.perform(get(base() + "/collaboration/tasks").cookie(browser.cookie())).andExpect(status().isOk()).andReturn()).path("items").size());
    }

    @Test
    void fullChatResultRequiresBothGroupPermissionAndOwnersChoice() throws Exception {
        // 本用例验证受限群，显式准备限制，避免依赖新群的默认回复方式。
        assertEquals(1, databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, group.getId())
            .set(ChatConversationRow::getResultPolicy, "summary_link")));
        startTask();
        var task = onlyTask();
        var browser = login();
        String path = base() + "/collaboration/tasks/" + task.getId() + "/inputs";
        String first = UUID.randomUUID().toString();
        writeAs(browser, path, Map.of("requestId", first, "expectedRevision", task.getRevision(), "text", "允许群内公布结果", "outputVisibility", "chat_full"), first)
            .andExpect(status().isUnprocessableEntity());
        assertEquals(1L, onlyTask().getInputRevision());
        assertEquals("summary_link", onlyTask().getOutputVisibility());
        assertEquals(1, databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, group.getId())
            .set(ChatConversationRow::getResultPolicy, "full_allowed")));
        String next = UUID.randomUUID().toString();
        writeAs(browser, path, Map.of("requestId", next, "expectedRevision", task.getRevision(), "text", "允许群内公布结果", "outputVisibility", "chat_full"), next)
            .andExpect(status().isOk());
        assertEquals("chat_full", onlyTask().getOutputVisibility());
        assertEquals(2L, onlyTask().getInputRevision());
    }

    private void startTask() {
        var message = receive(UUID.randomUUID().toString(), "统计本月考勤和工资");
        routing.route(enterprise, message.getId());
    }

    private AuthContext actor() {
        return identities.actor(enterprise, initiator.id(), binding.getId());
    }

    private CollaborationWorkItemRow work(String id) {
        return databaseAccess.mapper(CollaborationWorkItemMapper.class).selectById(id);
    }

    private Browser login() throws Exception {
        var initial = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var logged = mvc.perform(post("/api/v1/auth/login").cookie(initial.getResponse().getCookie("SESSION"))
            .header("X-CSRF-Token", data(initial).path("token").asText()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identifier", initiator.username(), "password", "chat-isolated-user-password"))))
            .andExpect(status().isOk()).andReturn();
        Cookie browser = logged.getResponse().getCookie("SESSION");
        var token = data(mvc.perform(get("/api/v1/auth/csrf").cookie(browser)).andExpect(status().isOk()).andReturn()).path("token").asText();
        return new Browser(browser, token);
    }

    private ResultActions writeAs(Browser browser, String path, Object body, String key) throws Exception {
        return mvc.perform(request(HttpMethod.POST, path).cookie(browser.cookie()).header("X-CSRF-Token", browser.csrf())
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private record Browser(Cookie cookie, String csrf) {
    }
}
