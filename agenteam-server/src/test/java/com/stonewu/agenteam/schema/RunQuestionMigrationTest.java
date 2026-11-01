package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.execution.RunQuestionMapper;
import com.stonewu.agenteam.mapper.permission.SysPermissionTableMapper;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.permission.entity.SysPermissionRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunQuestionMigrationTest {
    @Test
    void addsQuestionsToAnExistingSchemaWithoutChangingPermissionsOrRepeatingMigration() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.5.0.4").cleanDisabled(true).load().migrate();
            var permissions = access.mapper(SysPermissionTableMapper.class);
            long previous = permissions.selectCount(new LambdaQueryWrapper<SysPermissionRow>());
            assertTrue(previous > 0);
            var current = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.5.0.5").validateOnMigrate(true).cleanDisabled(true).load();
            assertEquals(1, current.migrate().migrationsExecuted);
            current.validate();
            assertEquals(previous, permissions.selectCount(new LambdaQueryWrapper<SysPermissionRow>()));
            assertEquals(0, access.mapper(RunQuestionMapper.class).selectCount(new LambdaQueryWrapper<RunQuestionRow>()));
            assertEquals(0, current.migrate().migrationsExecuted);
        }
    }
}
