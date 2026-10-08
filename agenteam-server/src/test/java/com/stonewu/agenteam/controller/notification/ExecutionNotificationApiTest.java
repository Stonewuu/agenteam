package com.stonewu.agenteam.controller.notification;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.user.entity.UserPreferenceRow;
import com.stonewu.agenteam.service.execution.ExecutionResultViewedService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用实际执行终态、事务回滚和资源停用验证通知来源，不直接制造通知记录。
 */
@Import(SharedEnterpriseTestEdition.class)
class ExecutionNotificationApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private NotificationDeliveryService notifications;

    @Autowired
    private ExecutionResultViewedService viewedResults;

    @Autowired
    private PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @BeforeEach
    void enableCompletionNotices() {
        databaseAccess.mapper(UserPreferenceMapper.class).update(new LambdaUpdateWrapper<UserPreferenceRow>().eq(UserPreferenceRow::getUserId, (admin)).set(UserPreferenceRow::getTaskCompletionNotifications, true));
    }

    @AfterEach
    void stopRemaining() {
        lifecycle.stopForUser(enterprise, admin);
        enableCompletionNotices();
    }

    @Test
    void finalResultsRespectPreferencesWithoutSuppressingFailuresOrCopyingErrorDetails() throws Exception {
        var first = submit();
        var lease = start();
        lifecycle.finish(lease, "completed", null, null);
        lifecycle.finish(lease, "failed", "EXECUTION_FAILED", "晚到错误不能改写结果");
        assertEquals(1, queuedNotices());
        databaseAccess.mapper(UserPreferenceMapper.class).update(new LambdaUpdateWrapper<UserPreferenceRow>().eq(UserPreferenceRow::getUserId, (admin)).set(UserPreferenceRow::getTaskCompletionNotifications, false));
        submit();
        lifecycle.finish(start(), "completed", null, null);
        assertEquals(1, queuedNotices());
        var failed = submit();
        lifecycle.finish(start(), "failed", "EXECUTION_FAILED", "不得复制到通知中的错误正文");
        var cancelled = submit();
        lifecycle.cancel(actor(), cancelled.path("runId").asText());
        var preview = data(preview(Map.of("input", input("预览结果留在原页面")), key()).andExpect(status().isAccepted()).andReturn());
        lifecycle.finish(start(), "completed", null, null);
        assertEquals("completed", state(preview.path("runId").asText()));
        assertEquals(2, queuedNotices());
        deliver();
        var page = notices();
        assertEquals(2, page.size());
        Set<String> targets = Set.of(page.get(0).path("targetId").asText(), page.get(1).path("targetId").asText());
        assertEquals(Set.of(first.path("conversationId").asText(), failed.path("conversationId").asText()), targets);
        assertFalse(page.toString().contains("错误正文"));
        assertFalse(page.toString().contains("预览结果"));
        assertEquals(2, count("notification"));
    }

    @Test
    void rollingBackTheFinalStateAlsoRollsBackTheNotificationAndAllowsTheSameLeaseToFinish() throws Exception {
        var accepted = submit();
        var lease = start();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            lifecycle.finish(lease, "completed", null, null);
            transaction.setRollbackOnly();
        });
        assertEquals("running", state(accepted.path("runId").asText()));
        assertEquals(0, queuedNotices());
        deliver();
        assertEquals(0, count("notification"));
        lifecycle.finish(lease, "completed", null, null);
        deliver();
        assertEquals(1, count("notification"));
        assertEquals("completed", state(accepted.path("runId").asText()));
    }

    @Test
    void losingResourceAccessRequestsStopAndNotifiesOnceEvenWhenCompletionNotificationsAreOff() throws Exception {
        databaseAccess.mapper(UserPreferenceMapper.class).update(new LambdaUpdateWrapper<UserPreferenceRow>().eq(UserPreferenceRow::getUserId, (admin)).set(UserPreferenceRow::getTaskCompletionNotifications, false));
        var accepted = submit();
        var lease = start();
        change(HttpMethod.PATCH, base() + "/resources/" + agent + "/status", Map.of("status", "disabled"), resourceRevision(), key()).andExpect(status().isOk());
        assertEquals("cancelling", state(accepted.path("runId").asText()));
        lifecycle.recheckEnterprise(enterprise);
        lifecycle.recheckAndRenew(lease);
        lifecycle.finish(lease, "completed", null, null);
        assertEquals("cancelled", state(accepted.path("runId").asText()));
        assertEquals(1, queuedNotices());
        deliver();
        var page = notices();
        assertEquals(1, page.size());
        assertEquals("permission", page.get(0).path("category").asText());
        assertEquals(accepted.path("conversationId").asText(), page.get(0).path("targetId").asText());
        assertTrue(page.get(0).path("readAt").isNull());
    }

    @Test
    void completionWaitsForTheVisiblePageAndAcknowledgementPreventsCreatingANotification() throws Exception {
        var accepted = submit();
        lifecycle.finish(start(), "completed", null, null);
        var job = databaseAccess.mapper(RunJobSqlMapper.class).selectOne(new LambdaQueryWrapper<BackgroundJobRow>()
            .eq(BackgroundJobRow::getEnterpriseId, enterprise).eq(BackgroundJobRow::getKind, "notification"));
        assertEquals(Duration.ofSeconds(5), Duration.between(job.getCreatedAt(), job.getAvailableAt()));
        assertTrue(notifications.candidates().stream().noneMatch(candidate -> candidate.id().equals(job.getId())));
        String path = viewedPath(accepted);
        mvc.perform(post(path).cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf))
            .andExpect(status().isNoContent());
        write(path, null, key()).andExpect(status().isNoContent());
        assertEquals("cancelled", databaseAccess.mapper(RunJobSqlMapper.class).selectById(job.getId()).getStatus());
        deliver();
        assertEquals(0, notices().size());
        assertEquals(0, count("notification"));
    }

    @Test
    void viewingOneResultDoesNotSuppressOtherConversations() throws Exception {
        var first = submit();
        lifecycle.finish(start(), "completed", null, null);
        var second = submit();
        lifecycle.finish(start(), "completed", null, null);
        write(viewedPath(first), null, key()).andExpect(status().isNoContent());
        deliver();
        var page = notices();
        assertEquals(1, page.size());
        assertEquals(second.path("conversationId").asText(), page.get(0).path("targetId").asText());
        assertTrue(page.get(0).path("readAt").isNull());
    }

    @Test
    void lateAcknowledgementReadsOnlyTheCompletedResultAndRemainsRepeatable() throws Exception {
        var completed = submit();
        lifecycle.finish(start(), "completed", null, null);
        var failed = submit();
        lifecycle.finish(start(), "failed", "EXECUTION_FAILED", "测试失败结果");
        deliver();
        write(viewedPath(completed), null, key()).andExpect(status().isNoContent());
        var firstRead = notices();
        write(viewedPath(completed), null, key()).andExpect(status().isNoContent());
        assertEquals(firstRead, notices());
        assertEquals(2, firstRead.size());
        for (var notice : firstRead) {
            if (notice.path("targetId").asText().equals(completed.path("conversationId").asText())) {
                assertFalse(notice.path("readAt").isNull());
            } else {
                assertEquals(failed.path("conversationId").asText(), notice.path("targetId").asText());
                assertTrue(notice.path("readAt").isNull());
            }
        }
    }

    @Test
    void unfinishedFailedMissingAndForeignResultsCannotBeAcknowledged() throws Exception {
        var accepted = submit();
        var lease = start();
        write(viewedPath(accepted), null, key()).andExpect(status().isConflict());
        lifecycle.finish(lease, "failed", "EXECUTION_FAILED", "测试失败结果");
        write(viewedPath(accepted), null, key()).andExpect(status().isConflict());
        write(base() + "/runs/" + key() + "/result-viewed", null, key()).andExpect(status().isNotFound());

        var completed = submit();
        lifecycle.finish(start(), "completed", null, null);
        String other = provisioning.create("其他通知企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        write("/api/v1/enterprises/" + other + "/runs/" + completed.path("runId").asText() + "/result-viewed", null, key())
            .andExpect(status().isNotFound());
        deliver();
        assertTrue(notices().findValues("readAt").stream().allMatch(JsonNode::isNull));
    }

    @Test
    void concurrentDeliveryAndAcknowledgementNeverLeaveAnUnreadCompletion() throws Exception {
        var completed = submit();
        lifecycle.finish(start(), "completed", null, null);
        makeNoticesAvailable();
        var candidate = notifications.candidates().stream().filter(item -> item.enterprise().equals(enterprise)).findFirst().orElseThrow();
        var gate = new CountDownLatch(1);
        var current = actor();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var sent = threads.submit(() -> {
                gate.await();
                notifications.deliver(candidate);
                return null;
            });
            var viewed = threads.submit(() -> {
                gate.await();
                viewedResults.viewed(current, completed.path("runId").asText());
                return null;
            });
            gate.countDown();
            sent.get(10, TimeUnit.SECONDS);
            viewed.get(10, TimeUnit.SECONDS);
        }
        var page = notices();
        assertTrue(page.size() <= 1);
        assertTrue(page.findValues("readAt").stream().noneMatch(JsonNode::isNull));
    }

    @Test
    void anotherMemberCannotAcknowledgeTheOwnersCompletion() throws Exception {
        var completed = submit();
        lifecycle.finish(start(), "completed", null, null);
        String member = key();
        users.insertUser(member, "notice-member-" + member, "unused-test-password-hash", "其他成员", false, Instant.now());
        users.addMember(enterprise, member, "其他成员", Instant.now());
        var other = new AuthContext(users.findById(member).orElseThrow(), enterprise, Set.of("conversation.view"));
        var denied = assertThrows(ApiException.class, () -> viewedResults.viewed(other, completed.path("runId").asText()));
        assertEquals(404, denied.getStatusCode().value());
        deliver();
        assertEquals(1, notices().size());
        assertTrue(notices().get(0).path("readAt").isNull());
    }

    private String viewedPath(JsonNode accepted) {
        return base() + "/runs/" + accepted.path("runId").asText() + "/result-viewed";
    }

    private AuthContext actor() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }

    private JsonNode submit() throws Exception {
        return data(write(base() + "/conversations", body(), key()).andExpect(status().isAccepted()).andReturn());
    }

    private JobLease start() {
        var lease = lifecycle.claim("通知验收工作进程").orElseThrow();
        lifecycle.start(lease).orElseThrow();
        return lease;
    }

    private String state(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    private int queuedNotices() {
        return Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "notification")));
    }

    private void deliver() {
        makeNoticesAvailable();
        for (var value : notifications.candidates().stream().filter(item -> item.enterprise().equals(enterprise)).toList()) {
            notifications.deliver(value);
            notifications.deliver(value);
        }
    }

    private void makeNoticesAvailable() {
        // 测试主动推进通知的可发送时间，不等待实际五秒，也不改变业务执行时间。
        databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>()
            .eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getKind, "notification")
            .eq(BackgroundJobRow::getStatus, "queued")
            .set(BackgroundJobRow::getAvailableAt, Instant.now().minusSeconds(1)));
    }

    private JsonNode notices() throws Exception {
        return data(mvc.perform(get(base() + "/notifications").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items");
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
