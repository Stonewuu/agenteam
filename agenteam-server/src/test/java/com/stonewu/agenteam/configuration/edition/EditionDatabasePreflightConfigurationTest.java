package com.stonewu.agenteam.configuration.edition;

import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.model.edition.entity.ProductEdition;
import com.stonewu.agenteam.service.edition.EditionDescriptor;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class EditionDatabasePreflightConfigurationTest {
    @Test
    void rejectsLegacyDataBeforeAnyMigrationBeanCanWriteAndDoesNotDependOnFlywayEnabled() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            var legacy = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.1.2").cleanDisabled(true).load();
            legacy.migrate();
            var tables = access.mapper(SchemaSnapshotMapper.class).tables();
            var attempted = new AtomicBoolean();
            runner(access.getDataSource(), ProductEdition.COMMUNITY)
                .withUserConfiguration(MigrationProbe.class).withBean(AtomicBoolean.class, () -> attempted)
                .run(application -> {
                    assertThat(application).hasFailed();
                    assertThat(attempted).isFalse();
                    assertThat(application.getStartupFailure()).hasStackTraceContaining("没有社区版部署记录");
                });
            runner(access.getDataSource(), ProductEdition.COMMUNITY)
                .withPropertyValues("spring.flyway.enabled=false")
                .run(application -> assertThat(application).hasFailed());
            assertThat(access.mapper(SchemaSnapshotMapper.class).tables()).containsExactlyInAnyOrderElementsOf(tables);
            assertThat(legacy.info().applied()).hasSize(4);
        }
    }

    @Test
    void allowsCommercialPackageToReachItsMigrationForExistingDatabase() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.1.2").cleanDisabled(true).load().migrate();
            var attempted = new AtomicBoolean();
            runner(access.getDataSource(), ProductEdition.PRO).withUserConfiguration(MigrationProbe.class)
                .withBean(AtomicBoolean.class, () -> attempted).run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(attempted).isTrue();
                    assertThat(access.mapper(SchemaSnapshotMapper.class).tables()).contains("agenteam_installation");
                });
        }
    }

    private ApplicationContextRunner runner(DataSource source, ProductEdition edition) {
        return new ApplicationContextRunner().withUserConfiguration(EditionDatabasePreflightConfiguration.class)
            .withBean(EditionDescriptor.class, () -> () -> edition).withBean(DataSource.class, () -> source);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MigrationProbe {
        @Bean
        String migrationProbe(DataSource source, AtomicBoolean attempted) {
            attempted.set(true);
            Flyway.configure().dataSource(source).locations("classpath:db/schema").cleanDisabled(true).load().migrate();
            return "迁移已执行";
        }
    }
}
