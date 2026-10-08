package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.test.schema.LegacyDemoAccountMapper;
import com.stonewu.agenteam.mapper.permission.SysPermissionTableMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.model.permission.entity.SysPermissionRow;
import com.stonewu.agenteam.model.test.schema.SchemaTableReference;
import com.stonewu.agenteam.support.IsolatedDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定校验已经发布的零号初始化结构；后续增量迁移由对应升级测试验证。
 */
class SchemaInitializationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void initializesCompleteSchemaOnceAndPreservesSeedData() throws Exception {
        var scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/schema/*.sql");
        assertTrue(Arrays.stream(scripts).anyMatch(resource -> "V0__init.sql".equals(resource.getFilename())),
            "已发布的初始化脚本必须保留，后续变更另加迁移");
        String script = new ClassPathResource("db/schema/V0__init.sql").getContentAsString(StandardCharsets.UTF_8);
        assertFalse(script.matches("(?is).*\\bALTER\\s+TABLE\\b.*"), "字段和约束应直接在建表时创建");
        assertFalse(script.matches("(?is).*\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\b.*"), "索引应直接在建表时创建");
        JsonNode expected;
        try (var stream = new ClassPathResource("schema/initialization-contract.json").getInputStream()) {
            expected = json.readTree(stream);
        }
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            var flyway = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).validateMigrationNaming(true)
                .target("0").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals("0", flyway.info().current().getVersion().toString());
            assertTrue(flyway.validateWithResult().validationSuccessful);
            var snapshot = access.mapper(SchemaSnapshotMapper.class);
            var tableNames = new ArrayList<String>();
            expected.get("definitionHashes").fieldNames().forEachRemaining(tableNames::add);
            assertEquals(tableNames.stream().sorted().toList(), snapshot.tables(), "业务表不能增加或遗漏");
            for (String table : tableNames) {
                var reference = new SchemaTableReference(table);
                String definition = normalizeTimeLiterals(table, snapshot.tableDefinition(reference).get("definition"));
                assertEquals(expected.get("definitionHashes").get(table).asText(), hash(definition), table + " 的完整定义改变");
            }
            var permissions = json.createArrayNode();
            var catalog = access.mapper(SysPermissionTableMapper.class).selectList(Wrappers.emptyWrapper());
            catalog.sort(Comparator.comparing(SysPermissionRow::getCode));
            for (var row : catalog) {
                permissions.add(values(row.getCode(), row.getName(), row.getScope(), row.getMenuKey(),
                    row.getMenuLabel(), row.getMenuPath(), row.getSortNo()));
            }
            assertEquals(expected.get("permissions"), permissions, "权限目录内容不能改变");
            var demoRows = access.mapper(LegacyDemoAccountMapper.class).selectList(Wrappers.emptyWrapper());
            assertEquals(1, demoRows.size(), "只初始化唯一演示控制记录");
            var demo = demoRows.getFirst();
            assertEquals(expected.get("demoAccount"), values(demo.getId(), Boolean.TRUE.equals(demo.getInitialized()) ? 1 : 0,
                    Boolean.TRUE.equals(demo.getEnabled()) ? 1 : 0, demo.getCleanupStatus(), demo.getCleanupRequestedAt(),
                    demo.getLastCleanedAt(), demo.getLastCleanedDate(), demo.getPasswordChangedAt(), demo.getRevision()),
                "演示控制记录不能改变");
            assertEquals(1, snapshot.foreignKeyChecks(), "初始化后仍须启用外键检查");
            assertEquals(0, flyway.migrate().migrationsExecuted, "后续启动不能重复初始化");
            assertTrue(flyway.validateWithResult().validationSuccessful);
            var restarted = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).validateMigrationNaming(true)
                .target("0").load();
            assertEquals(0, restarted.migrate().migrationsExecuted, "重新创建迁移工具后也不能重复初始化");
            assertEquals("0", restarted.info().current().getVersion().toString());
            assertEquals(1, restarted.info().applied().length, "合并后只保留一条初始化记录");
            assertTrue(restarted.validateWithResult().validationSuccessful);
        }
    }

    private JsonNode values(Object... values) throws Exception {
        return json.readTree(json.writeValueAsBytes(Arrays.asList(values)));
    }

    private String normalizeTimeLiterals(String table, String definition) {
        if (!table.equals("scheduled_task")) {
            return definition;
        }
        // MySQL 的建表与改表会给同一时间字符串保存不同的字符集标注，比较时仅统一这两个常量。
        return definition.lines().map(line -> {
            if (line.contains("`ck_scheduled_task_4`")) {
                return line.replace("_utf8mb4'00:00:00'", "'00:00:00'")
                    .replace("_utf8mb4'23:59:00'", "'23:59:00'");
            }
            return line;
        }).reduce((left, right) -> left + "\n" + right).orElseThrow();
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
