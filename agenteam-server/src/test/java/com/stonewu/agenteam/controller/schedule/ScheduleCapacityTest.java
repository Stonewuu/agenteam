package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduledNotificationTargetMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledNotificationTargetRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleActionRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.schedule.ScheduleActionReconciler;
import com.stonewu.agenteam.service.schedule.ScheduleActionStore;
import com.stonewu.agenteam.service.schedule.ScheduleActionTransactions;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.schedule.ScheduleQueryService;
import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 两个独立工作对象并发领取同一数据库，测量千条同分钟到期计划和同时读取接口的耗时。 */
@Import(SharedEnterpriseTestEdition.class)
class ScheduleCapacityTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private ScheduleManagementService management;
    @Autowired private ScheduleQueryService queries;
    @Autowired private ScheduleTriggerService triggers;
    @Autowired private ScheduleActionTransactions actions;
    @Autowired private ScheduleActionStore store;
    @Autowired private ScheduleActionReconciler reconciler;
    @Autowired private ScheduleSqlMapper schedules;
    @Autowired private ScheduledNotificationTargetMapper targets;
    @Autowired private ScheduleOccurrenceSqlMapper occurrences;
    @Autowired private NotificationSqlMapper notices;
    @Autowired private BackgroundJobSqlMapper jobs;
    @Autowired private IdentityQueryMapper members;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void oneThousandDuePlansAreSubmittedOnceWhileOtherRequestsRemainResponsive() throws Exception {
        seed(1000);
        var done = new AtomicBoolean();
        var prepared = new AtomicInteger();
        var peak = new AtomicLong();
        var reads = Collections.synchronizedList(new ArrayList<Long>());
        var firstWorker = new ScheduleActionWorker(actions);
        var secondWorker = new ScheduleActionWorker(actions);
        Instant started = Instant.now();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        try (var pool = Executors.newFixedThreadPool(5)) {
            var probe = pool.submit(() -> {
                while (!done.get() && System.nanoTime() < deadline) {
                    long before = System.nanoTime();
                    mvc.perform(get(base() + "/schedules").param("limit", "20").cookie(cookie)).andExpect(status().isOk());
                    reads.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before));
                    long pending = jobs.selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
                        .eq(BackgroundJobRow::getKind, "scheduled_action").in(BackgroundJobRow::getStatus, "queued", "leased"));
                    peak.accumulateAndGet(pending, Math::max);
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                }
                return null;
            });
            var firstScheduler = pool.submit(() -> scheduleUntilEmpty(deadline));
            var secondScheduler = pool.submit(() -> scheduleUntilEmpty(deadline));
            var first = pool.submit(() -> prepareUntilDone(firstWorker, prepared, deadline));
            var second = pool.submit(() -> prepareUntilDone(secondWorker, prepared, deadline));
            try {
                firstScheduler.get(4, TimeUnit.MINUTES);
                secondScheduler.get(4, TimeUnit.MINUTES);
                first.get(4, TimeUnit.MINUTES);
                second.get(4, TimeUnit.MINUTES);
                List<ScheduledOccurrenceRow> active;
                while (!(active = store.candidates()).isEmpty() && System.nanoTime() < deadline) {
                    active.forEach(reconciler::reconcile);
                }
            } finally {
                done.set(true);
                probe.get(15, TimeUnit.SECONDS);
            }
        } finally {
            firstWorker.close();
            secondWorker.close();
        }
        var all = occurrences.selectList(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getEnterpriseId, enterprise));
        assertEquals(1000, all.size());
        assertEquals(1000, all.stream().map(ScheduledOccurrenceRow::getScheduleId).distinct().count());
        assertTrue(all.stream().allMatch(row -> "completed".equals(row.getStatus())));
        assertEquals(1000, notices.selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)));
        assertEquals(0, schedules.selectCount(new LambdaQueryWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterprise).isNotNull(ScheduledTaskRow::getActiveOccurrenceId)));
        assertEquals(0, jobs.selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getKind, "scheduled_action").ne(BackgroundJobRow::getStatus, "completed")));
        assertEquals(0, count("agent_run"));
        assertEquals(0, reserved());
        assertFalse(reads.isEmpty());
        var sorted = reads.stream().sorted().toList();
        long p95 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
        long elapsed = Duration.between(started, Instant.now()).toMillis();
        var report = Map.of("planCount", 1000, "workerInstances", 2, "schedulerThreads", 2, "sameJvm", true,
            "elapsedMillis", elapsed, "readSamples", reads.size(), "readP95Millis", p95, "readMaxMillis", sorted.getLast(),
            "observedPendingPeak", peak.get(), "finishedAt", Instant.now().toString());
        Files.writeString(Path.of("target/schedule-capacity-result.json"), json.writeValueAsString(report));
        System.out.println("定时计划容量验证结果：" + json.writeValueAsString(report));
        assertTrue(elapsed < TimeUnit.MINUTES.toMillis(4), "千条计划应在本次验证截止前全部处理完毕");
        assertTrue(p95 < 5000, "后台处理期间计划查询不应长时间失去响应");
    }

    @Test
    void fiftyRecipientsKeepPartialFailureInTheTotalWithoutBlockingActiveMembers() {
        var selected = new ArrayList<String>();
        selected.add(admin);
        for (int index = 1; index < 50; index++) {
            var member = EnterpriseTestData.member(users, permissions, enterprise, "batch_" + UUID.randomUUID().toString().substring(0, 12),
                "batch-test-password-2026", "批量接收成员" + index, List.of(permissions.builtinRoleId(enterprise, "member")));
            selected.add(member.id());
        }
        var plan = management.create(actor(), request(selected));
        var occurrence = triggers.manual(actor(), plan.id(), UUID.randomUUID().toString());
        members.update(new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterprise)
            .in(EnterpriseMemberRow::getUserId, selected.subList(1, 11)).set(EnterpriseMemberRow::getStatus, "disabled"));
        var worker = new ScheduleActionWorker(actions);
        try {
            assertTrue(worker.runOnce());
        } finally {
            worker.close();
        }
        reconciler.reconcile(occurrences.selectOne(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getId, occurrence.id())));
        var result = queries.occurrence(actor(), plan.id(), occurrence.id());
        assertEquals("partially_failed", result.occurrence().status());
        assertEquals(50, result.occurrence().actionResult().get("totalCount"));
        assertEquals(40, result.occurrence().actionResult().get("inAppCount"));
        assertEquals(10, result.occurrence().actionResult().get("blockedCount"));
        assertEquals(50, result.recipients().size());
        assertEquals(40, notices.selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)));
    }

    private void scheduleUntilEmpty(long deadline) {
        while (System.nanoTime() < deadline) {
            var candidates = triggers.candidates();
            if (candidates.isEmpty()) {
                return;
            }
            candidates.forEach(triggers::process);
        }
        throw new IllegalStateException("定时计划调度容量验证超时");
    }

    private void prepareUntilDone(ScheduleActionWorker worker, AtomicInteger count, long deadline) {
        while (count.get() < 1000 && System.nanoTime() < deadline) {
            if (worker.runOnce()) {
                count.incrementAndGet();
            } else {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        }
    }

    private void seed(int count) {
        var template = management.create(actor(), request(List.of(admin)));
        var original = schedules.selectOne(new LambdaQueryWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getId, template.id()));
        Instant due = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        var records = new ArrayList<ScheduledTaskRow>();
        var recipients = new ArrayList<ScheduledNotificationTargetRow>();
        for (int index = 0; index < count; index++) {
            var row = new ScheduledTaskRow();
            BeanUtils.copyProperties(original, row);
            row.setId(UUID.randomUUID().toString());
            row.setName("容量验证计划" + index);
            row.setEnabled(1);
            row.setFrequency("once");
            row.setLocalDate(due.atZone(ZoneOffset.UTC).toLocalDate());
            row.setLocalTime(due.atZone(ZoneOffset.UTC).toLocalTime().toString());
            row.setTimezone("UTC");
            row.setNextRunAt(due);
            row.setLastCheckedAt(due.minusSeconds(1));
            records.add(row);
            var target = new ScheduledNotificationTargetRow();
            target.setId(UUID.randomUUID().toString());
            target.setEnterpriseId(enterprise);
            target.setScheduleId(row.getId());
            target.setRecipientUserId(admin);
            target.setCreatedAt(Instant.now());
            recipients.add(target);
        }
        schedules.insert(records, 100);
        targets.insert(recipients, 100);
    }

    private AuthContext actor() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }

    private ScheduleWriteRequest request(List<String> recipients) {
        return new ScheduleWriteRequest("批量通知模板", null, null, null, "daily", null, "09:15", List.of(), null, "UTC", false, 0,
            new ScheduleActionRequest("notification.send", 1, Map.of("title", "容量测试通知", "body", "仅隔离测试环境", "recipients",
                recipients.stream().map(user -> Map.of("userId", user, "connectionIds", List.of())).toList())));
    }
}
