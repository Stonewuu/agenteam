package com.stonewu.agenteam.schema;

import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaPluginSchemaMigrationTest {
    @Test
    void upgradesExistingSchemaAndDoesNotRepeatMigrationOnRestart() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.3.0.2").cleanDisabled(true).load().migrate();
            assertFalse(access.mapper(SchemaSnapshotMapper.class).tables().contains("java_plugin_installation"));
            var upgrade = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.3.0.3").cleanDisabled(true).validateMigrationNaming(true).load();
            assertEquals(1, upgrade.migrate().migrationsExecuted);
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables().contains("java_plugin_installation"));
            assertEquals(0, upgrade.migrate().migrationsExecuted);
            assertTrue(upgrade.validateWithResult().validationSuccessful);
        }
    }
}
