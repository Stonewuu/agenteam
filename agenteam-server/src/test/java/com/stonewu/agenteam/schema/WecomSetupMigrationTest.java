package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.integration.WecomBotEnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.WecomMemberAliasMapper;
import com.stonewu.agenteam.mapper.integration.WecomSetupJobMapper;
import com.stonewu.agenteam.model.integration.entity.WecomBotEnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.WecomMemberAliasRow;
import com.stonewu.agenteam.model.integration.entity.WecomSetupJobRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WecomSetupMigrationTest {
    @Test
    void upgradesTheCurrentDevelopmentSchemaAndRestartsWithoutRepeatingTheMigration() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.5.0.2").cleanDisabled(true).load().migrate();
            var current = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.5.0.4").validateOnMigrate(true).cleanDisabled(true).load();
            assertEquals(2, current.migrate().migrationsExecuted);
            current.validate();
            assertEquals(0, current.migrate().migrationsExecuted);
            assertEquals(0, access.mapper(WecomSetupJobMapper.class).selectCount(new LambdaQueryWrapper<WecomSetupJobRow>()));
            assertEquals(0, access.mapper(WecomBotEnterpriseMapper.class).selectCount(new LambdaQueryWrapper<WecomBotEnterpriseRow>()));
            assertEquals(0, access.mapper(WecomMemberAliasMapper.class).selectCount(new LambdaQueryWrapper<WecomMemberAliasRow>()));
        }
    }
}
