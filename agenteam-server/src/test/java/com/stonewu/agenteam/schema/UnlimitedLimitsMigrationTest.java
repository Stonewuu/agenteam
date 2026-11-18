package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaPolicyFixtureMapper;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.usage.entity.QuotaPolicyRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UnlimitedLimitsMigrationTest {
    @Test
    void upgradesExistingUnlimitedQuotaAndPreservesZeroQuota() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.6.0.5").cleanDisabled(true).load().migrate();
            var user = new AppUserRow();
            user.setId(UUID.randomUUID().toString());
            user.setUsername("limits_" + user.getId());
            user.setUsernameNormalized(user.getUsername());
            user.setPasswordHash("isolated-test-hash");
            user.setDisplayName("限制升级测试成员");
            user.setCreatedAt(Instant.now());
            user.setUpdatedAt(Instant.now());
            access.mapper(AppUserTableMapper.class).insert(user);
            var enterprise = new EnterpriseRow();
            enterprise.setId(UUID.randomUUID().toString());
            enterprise.setName("限制升级测试企业");
            enterprise.setCreatedBy(user.getId());
            enterprise.setCreatedAt(Instant.now());
            enterprise.setUpdatedAt(Instant.now());
            enterprise.setQuotaPeriodStart(Instant.now());
            enterprise.setQuotaPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
            access.mapper(EnterpriseTableMapper.class).insert(enterprise);
            var mapper = access.mapper(QuotaPolicyFixtureMapper.class);
            var unlimited = policy(enterprise.getId(), "enterprise", null);
            var forbidden = policy(enterprise.getId(), "role", 0L);
            mapper.insert(unlimited);
            mapper.insert(forbidden);
            var migration = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .validateOnMigrate(true).cleanDisabled(true).load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            assertEquals(-1L, mapper.selectById(unlimited.getId()).getMonthlyLimit());
            assertEquals(0L, mapper.selectById(forbidden.getId()).getMonthlyLimit());
            assertEquals(1, mapper.update(new LambdaUpdateWrapper<QuotaPolicyRow>()
                .eq(QuotaPolicyRow::getId, unlimited.getId()).set(QuotaPolicyRow::getMonthlyLimit, 2_000_000_000L)));
            assertThrows(RuntimeException.class, () -> mapper.update(new LambdaUpdateWrapper<QuotaPolicyRow>()
                .eq(QuotaPolicyRow::getId, unlimited.getId()).set(QuotaPolicyRow::getMonthlyLimit, -2L)));
            var snapshot = access.mapper(TestDatabaseAdministrationMapper.class);
            for (String table : new String[]{"collaboration_task", "collaboration_work_item"}) {
                assertEquals("YES", snapshot.columnNullable(table, "deadline_at"));
            }
            assertTrue(migration.validateWithResult().validationSuccessful);
            assertEquals(0, migration.migrate().migrationsExecuted);
        }
    }

    @Test
    void emptyDatabaseCanInitializeAndRestart() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var migration = Flyway.configure().dataSource(database.databaseAccess().getDataSource())
                .locations("classpath:db/schema").validateOnMigrate(true).cleanDisabled(true).load();
            assertTrue(migration.migrate().migrationsExecuted > 0);
            assertEquals("0.6.2.1", migration.info().current().getVersion().toString());
            assertTrue(migration.validateWithResult().validationSuccessful);
            assertEquals(0, migration.migrate().migrationsExecuted);
        }
    }

    private QuotaPolicyRow policy(String enterprise, String type, Long limit) {
        var row = new QuotaPolicyRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setSubjectType(type);
        row.setSubjectId(enterprise);
        row.setMonthlyLimit(limit);
        return row;
    }
}
