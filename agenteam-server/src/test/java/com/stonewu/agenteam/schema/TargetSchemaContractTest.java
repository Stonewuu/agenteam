package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.permission.SysPermissionTableMapper;
import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import com.stonewu.agenteam.model.permission.entity.SysPermissionRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提前在真实 MySQL 中验证完整目标结构，不能用语法解析代替数据库约束验证。
 */
class TargetSchemaContractTest {

    private static final Path CONTRACTS = Path.of("src/test/resources/contracts");

    @Test
    void completeTargetSchemaExecutesAndMatchesTheFieldContract() throws Exception {
        try (var database = new IsolatedDatabase();
             var connection = database.connection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(CONTRACTS.resolve("schema-target.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(CONTRACTS.resolve("permission-seed.sql")));
            var tables = new ObjectMapper().readTree(CONTRACTS.resolve("data-model.json").toFile()).get("tables");
            var databaseAccess = database.databaseAccess();
            assertEquals(tables.size(), databaseAccess.mapper(TestDatabaseAdministrationMapper.class).countTables());
            int expectedForeignKeys = 0;
            for (var table : tables) {
                String name = table.get("name").asText();
                assertEquals(table.get("columns").size(), databaseAccess.mapper(TestDatabaseAdministrationMapper.class).countColumns(name), name);
                for (var column : table.get("columns")) {
                    assertEquals(1, databaseAccess.mapper(TestDatabaseAdministrationMapper.class).countColumn(name, column.get("name").asText()), name + "." + column.get("name").asText());
                }
                expectedForeignKeys += table.get("foreignKeys").size();
            }
            assertEquals(expectedForeignKeys, databaseAccess.mapper(TestDatabaseAdministrationMapper.class).countForeignKeys());
            // 旧结构种子与当前版本提供的接口分别核对，不将历史权限误当作当前可调用能力。
            var permissions = new ObjectMapper().readTree(CONTRACTS.resolve("historical-permissions.json").toFile()).get("permissions");
            assertEquals(permissions.size(), Math.toIntExact(databaseAccess.mapper(SysPermissionTableMapper.class).selectCount(new LambdaQueryWrapper<SysPermissionRow>())));
            assertTrue(databaseAccess.mapper(TestDatabaseAdministrationMapper.class).version().startsWith("8.4."));
        }
    }
}
