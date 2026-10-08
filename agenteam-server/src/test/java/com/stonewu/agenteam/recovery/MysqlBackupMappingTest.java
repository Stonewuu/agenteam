package com.stonewu.agenteam.recovery;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.test.recovery.BackupFixtureMapper;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实表、空值和日志位置，验证恢复检查的数据库映射。
 */
class MysqlBackupMappingTest {
    @Test
    void contentDigestsPreserveNullColumnsAndBinaryLogPositions() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            var users = access.mapper(AppUserTableMapper.class);
            var row = new AppUserRow();
            row.setId(UUID.randomUUID().toString());
            row.setUsername("backup-member");
            row.setUsernameNormalized("backup-member");
            row.setPasswordHash("test-hash");
            row.setDisplayName("备份测试成员");
            row.setCreatedAt(Instant.now());
            row.setUpdatedAt(row.getCreatedAt());
            assertEquals(1, users.insert(row));

            try (var backup = new MysqlBackupFixture(access, Path.of("target", "backup-mapping-" + UUID.randomUUID()))) {
                var original = backup.tableContents(access);
                assertEquals(1L, ((Map<?, ?>) original.get("app_user")).get("rows"));
                assertEquals(original, backup.tableContents(access));
                users.update(new LambdaUpdateWrapper<AppUserRow>().eq(AppUserRow::getId, row.getId())
                    .set(AppUserRow::getEmail, "backup@example.test"));
                assertNotEquals(original.get("app_user"), backup.tableContents(access).get("app_user"));
                users.update(new LambdaUpdateWrapper<AppUserRow>().eq(AppUserRow::getId, row.getId()).set(AppUserRow::getEmail, null));
                assertEquals(original, backup.tableContents(access));

                var metadata = access.mapper(BackupFixtureMapper.class);
                var before = metadata.position();
                backup.rotateLog();
                var after = metadata.position();
                assertNotEquals(before.file(), after.file());
                assertTrue(after.offset() > 0);
                assertTrue(metadata.logFiles().contains(after.file()));
                assertTrue(metadata.bufferPoolSize() > 0);
            }
        }
    }
}
