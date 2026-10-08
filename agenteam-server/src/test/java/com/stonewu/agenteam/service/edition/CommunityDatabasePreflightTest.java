package com.stonewu.agenteam.service.edition;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.auth.SystemSuperAdminLockMapper;
import com.stonewu.agenteam.mapper.edition.InstallationMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.model.auth.entity.SystemSuperAdminLockRow;
import com.stonewu.agenteam.model.edition.entity.InstallationRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityDatabasePreflightTest {
    private final CommunityDatabasePreflight preflight = new CommunityDatabasePreflight();

    @Test
    void acceptsEmptyAndNewUninitializedCommunityDatabaseWithoutWritingDuringPreflight() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            assertThat(access.mapper(SchemaSnapshotMapper.class).tables()).isEmpty();
            preflight.verify(access.getDataSource());
            assertThat(access.mapper(SchemaSnapshotMapper.class).tables()).isEmpty();
            database.initialize();
            var before = access.mapper(InstallationMapper.class).selectById(1);
            preflight.verify(access.getDataSource());
            var after = access.mapper(InstallationMapper.class).selectById(1);
            assertThat(after.getRevision()).isEqualTo(before.getRevision());
            assertThat(after.getInstallationId()).isNull();
            assertThat(after.getInitialEnterpriseId()).isNull();
        }
    }

    @Test
    void rejectsLegacyDatabaseBeforeChangingMigrationHistoryOrCreatingNewTables() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            var legacy = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.1.2").cleanDisabled(true).load();
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(4);
            var tables = access.mapper(SchemaSnapshotMapper.class).tables();
            assertThatThrownBy(() -> preflight.verify(access.getDataSource()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有社区版部署记录");
            assertThat(access.mapper(SchemaSnapshotMapper.class).tables()).containsExactlyInAnyOrderElementsOf(tables);
            assertThat(legacy.info().applied()).hasSize(4);
            assertThat(legacy.validateWithResult().validationSuccessful).isTrue();
        }
    }

    @Test
    void acceptsFixedEnterpriseAndRejectsAdditionalEnterpriseAndCommercialMarker() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String first = enterprise(access, owner);
            var administrator = new SystemSuperAdminLockRow();
            administrator.setId(1);
            administrator.setUserId(owner);
            administrator.setCreatedAt(Instant.now());
            access.mapper(SystemSuperAdminLockMapper.class).insert(administrator);
            String installationId = UUID.randomUUID().toString();
            assertThat(access.mapper(InstallationMapper.class).update(new LambdaUpdateWrapper<InstallationRow>()
                .eq(InstallationRow::getId, 1).eq(InstallationRow::getRevision, 1L)
                .set(InstallationRow::getInstallationId, installationId)
                .set(InstallationRow::getInitialEnterpriseId, first)
                .set(InstallationRow::getInitializedAt, Instant.now())
                .set(InstallationRow::getRevision, 2L))).isEqualTo(1);
            assertThatCode(() -> preflight.verify(access.getDataSource())).doesNotThrowAnyException();
            enterprise(access, owner);
            assertThatThrownBy(() -> preflight.verify(access.getDataSource()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只支持初始化时建立的企业");
            assertThat(access.mapper(InstallationMapper.class).selectById(1).getInstallationId()).isEqualTo(installationId);
            assertThat(access.mapper(InstallationMapper.class).update(new LambdaUpdateWrapper<InstallationRow>()
                .eq(InstallationRow::getId, 1).eq(InstallationRow::getRevision, 2L)
                .set(InstallationRow::getEdition, "pro").set(InstallationRow::getRevision, 3L))).isEqualTo(1);
            assertThatThrownBy(() -> preflight.verify(access.getDataSource()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("属于商业版");
        }
    }

    private String user(MybatisTestDatabase database) {
        var user = new AppUserRow();
        user.setId(UUID.randomUUID().toString());
        user.setUsername("edition_" + user.getId());
        user.setUsernameNormalized(user.getUsername());
        user.setPasswordHash("isolated-edition-fixture");
        user.setDisplayName("部署检查测试用户");
        user.setIsSuperAdmin(1);
        database.mapper(AppUserTableMapper.class).insert(user);
        return user.getId();
    }

    private String enterprise(MybatisTestDatabase database, String creator) {
        var enterprise = new EnterpriseRow();
        enterprise.setId(UUID.randomUUID().toString());
        enterprise.setName("部署检查测试企业");
        enterprise.setCreatedBy(creator);
        enterprise.setCreatedAt(Instant.now());
        enterprise.setUpdatedAt(Instant.now());
        enterprise.setQuotaPeriodStart(Instant.now());
        enterprise.setQuotaPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        database.mapper(EnterpriseTableMapper.class).insert(enterprise);
        return enterprise.getId();
    }
}
