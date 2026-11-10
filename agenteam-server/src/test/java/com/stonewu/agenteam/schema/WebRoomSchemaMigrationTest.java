package com.stonewu.agenteam.schema;

import com.stonewu.agenteam.mapper.permission.SysPermissionTableMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 已发布版本升级后可独立保存网页房间，重复启动不再执行迁移。 */
class WebRoomSchemaMigrationTest {
    @Test
    void upgradesPublishedDatabaseOnce() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            var config = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema");
            config.target("0.5.0.5").load().migrate();
            var permissions = access.mapper(SysPermissionTableMapper.class);
            long previousPermissions = permissions.selectCount(null);
            assertTrue(previousPermissions > 0);
            var upgrade = config.target("0.6.0.1").validateOnMigrate(true).cleanDisabled(true).load();
            assertEquals(1, upgrade.migrate().migrationsExecuted);
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables().contains("collaboration_room_entry"));
            assertEquals(previousPermissions, permissions.selectCount(null));
            assertTrue(upgrade.validateWithResult().validationSuccessful);
            assertEquals(0, upgrade.migrate().migrationsExecuted);
            var attachments = config.target("0.6.0.2").load();
            assertEquals(1, attachments.migrate().migrationsExecuted);
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables().contains("collaboration_room_entry_file"));
            assertTrue(attachments.validateWithResult().validationSuccessful);
            assertEquals(0, attachments.migrate().migrationsExecuted);
            var mentions = config.target("0.6.0.3").load();
            assertEquals(1, mentions.migrate().migrationsExecuted);
            assertTrue(mentions.validateWithResult().validationSuccessful);
            assertEquals(0, mentions.migrate().migrationsExecuted);
            config.target("0.6.0.4").load().migrate();
            var queues = config.target("0.6.0.5").load();
            assertEquals(1, queues.migrate().migrationsExecuted);
            assertTrue(queues.validateWithResult().validationSuccessful);
            assertEquals(0, queues.migrate().migrationsExecuted);
        }
    }
}
