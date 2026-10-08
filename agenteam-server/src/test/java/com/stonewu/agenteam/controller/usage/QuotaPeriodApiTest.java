package com.stonewu.agenteam.controller.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.mapper.usage.QuotaEntryTableMapper;
import com.stonewu.agenteam.mapper.usage.QuotaSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.enterprise.request.EnterpriseUpdatePayload;
import com.stonewu.agenteam.model.usage.entity.QuotaBucketRow;
import com.stonewu.agenteam.model.usage.entity.QuotaEntryRow;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import com.stonewu.agenteam.service.enterprise.EnterpriseMetadataService;
import com.stonewu.agenteam.service.usage.QuotaPeriodService;
import com.stonewu.agenteam.service.usage.QuotaReservationService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;

/**
 * 使用真实企业变更和次数事务，验证尚无用量时的时区变更与跨月预留。
 */
@Import(SharedEnterpriseTestEdition.class)
class QuotaPeriodApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private QuotaPeriodService periods;

    @Autowired
    private QuotaReservationService quotas;

    @Autowired
    private EnterpriseMetadataService metadata;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoSpyBean
    private Clock clock;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void updatingTimezoneWithoutAnyUsageKeepsTheFirstReservationInTheExistingMonth() {
        var original = period();
        assertEquals(0, count("quota_bucket"));
        changeZone("America/Los_Angeles");
        assertEquals(original, period());
        String run = UUID.randomUUID().toString();
        transaction(() -> quotas.reserve(actor(), run));
        assertEquals("Asia/Shanghai", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaSqlMapper.class).selectList(new LambdaQueryWrapper<QuotaBucketRow>().select(QuotaBucketRow::getTimezone).eq(QuotaBucketRow::getEnterpriseId, (enterprise))).stream().map(fixtureRecord -> fixtureRecord.getTimezone()).toList()));
        assertEquals("America/Los_Angeles", metadata.get(actor()).pendingQuotaTimezone());
        freeze(original.end().minusMillis(1));
        assertEquals(original, period());
        freeze(original.end());
        var changed = period();
        assertEquals(original.end(), changed.start());
        assertEquals("America/Los_Angeles", changed.timezone());
        assertNull(metadata.get(actor()).pendingQuotaTimezone());
        assertEquals(1, reserved());
    }

    @Test
    void queuedReservationsMoveAtStartButConsumedRunsKeepTheirOriginalPeriod() {
        var original = period();
        changeZone("America/Los_Angeles");
        String consumed = UUID.randomUUID().toString(), queued = UUID.randomUUID().toString();
        transaction(() -> {
            quotas.reserve(actor(), consumed);
            quotas.consume(actor(), consumed);
            quotas.reserve(actor(), queued);
        });
        assertEquals(1, reserved());
        freeze(original.end());
        transaction(() -> quotas.consume(actor(), queued));
        assertEquals(2, count("quota_bucket"));
        assertEquals(0, reserved());
        assertEquals(1, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaSqlMapper.class).selectList(new LambdaQueryWrapper<QuotaBucketRow>().select(QuotaBucketRow::getUsedCount).eq(QuotaBucketRow::getEnterpriseId, (enterprise)).eq(QuotaBucketRow::getTimezone, "Asia/Shanghai")).stream().map(fixtureRecord -> (fixtureRecord.getUsedCount() == null ? null : Math.toIntExact(fixtureRecord.getUsedCount()))).toList()));
        assertEquals(1, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaSqlMapper.class).selectList(new LambdaQueryWrapper<QuotaBucketRow>().select(QuotaBucketRow::getUsedCount).eq(QuotaBucketRow::getEnterpriseId, (enterprise)).eq(QuotaBucketRow::getTimezone, "America/Los_Angeles")).stream().map(fixtureRecord -> (fixtureRecord.getUsedCount() == null ? null : Math.toIntExact(fixtureRecord.getUsedCount()))).toList()));
        assertEquals("released", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaEntryTableMapper.class).selectJoinList(QuotaEntryRow.class, JoinWrappers.lambda(QuotaEntryRow.class).select(QuotaEntryRow::getState).innerJoin(QuotaBucketRow.class, on -> on.eq(QuotaBucketRow::getId, QuotaEntryRow::getBucketId)).eq(QuotaEntryRow::getRunId, (queued)).eq(QuotaBucketRow::getTimezone, "Asia/Shanghai")).stream().map(fixtureRecord -> fixtureRecord.getState()).toList()));
        freeze(period().end().plusSeconds(1));
        transaction(() -> quotas.consume(actor(), consumed));
        assertEquals(2, count("quota_bucket"));
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).conversationManagementApiPreviewsStayOutOfTheNormalListAndExpireWithoutDeletingUsageOrOtherConversationsObject(enterprise)));
    }

    @Test
    void aSecondTimezoneEditAfterTheMonthBoundaryAppliesOnlyToTheFollowingMonth() {
        var original = period();
        changeZone("America/Los_Angeles");
        freeze(original.end().plusSeconds(86400));
        changeZone("Pacific/Kiritimati");
        var middle = period();
        assertEquals("America/Los_Angeles", middle.timezone());
        assertEquals("Pacific/Kiritimati", metadata.get(actor()).pendingQuotaTimezone());
        freeze(middle.end());
        var after = period();
        assertEquals(middle.end(), after.start());
        assertEquals("Pacific/Kiritimati", after.timezone());
        assertEquals(0, count("quota_bucket"));
    }

    private AuthContext actor() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }

    private QuotaPeriod period() {
        return new TransactionTemplate(transactions).execute(ignored -> periods.current(enterprise));
    }

    private void transaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> work.run());
    }

    private void freeze(Instant instant) {
        doReturn(instant).when(clock).instant();
        doReturn(instant.toEpochMilli()).when(clock).millis();
    }

    private void changeZone(String zone) {
        long revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(EnterpriseTableMapper.class).selectList(new LambdaQueryWrapper<EnterpriseRow>().select(EnterpriseRow::getRevision).eq(EnterpriseRow::getId, (enterprise))).stream().map(fixtureRecord -> fixtureRecord.getRevision()).toList());
        metadata.update(actor(), new EnterpriseUpdatePayload(null, null, null, zone, null), revision, Set.of("timezone"));
    }
}
