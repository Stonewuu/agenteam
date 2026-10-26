package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.FeishuSetupJobMapper;
import com.stonewu.agenteam.mapper.integration.FeishuManagementConnectionMapper;
import com.stonewu.agenteam.model.integration.entity.FeishuSetupJobRow;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证旧机器人分类时保留企业登录和个人通知，且重复启动不修改配置。 */
class IntegrationPurposeMigrationTest {
    @Test
    void enterpriseSetupUpgradeKeepsExistingJobsAndCanRunAgain() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.5.0.1").cleanDisabled(true).load().migrate();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            String connection = connection(access, enterprise, owner, "feishu", false, false);
            var row = new FeishuSetupJobRow();
            row.setId(UUID.randomUUID().toString()); row.setEnterpriseId(enterprise); row.setOwnerUserId(owner);
            row.setOwnerSessionVersion(1L); row.setSystemAccess(false); row.setKind("management"); row.setConnectionId(connection);
            row.setInputJson("{\"kind\":\"management\",\"name\":\"已有管理应用\"}"); row.setStage("completed");
            row.setNextAttemptAt(Instant.now()); row.setCreatedAt(Instant.now()); row.setUpdatedAt(Instant.now());
            access.mapper(FeishuSetupJobMapper.class).insert(row);
            var migration = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema").cleanDisabled(true).load();
            assertTrue(migration.migrate().migrationsExecuted > 0);
            var restored = access.mapper(FeishuSetupJobMapper.class).selectById(row.getId());
            assertEquals("completed", restored.getStage());
            assertEquals(connection, restored.getConnectionId());
            assertEquals(1L, restored.getConnectionRevision());
            assertEquals(1L, restored.getCredentialRevision());
            assertEquals(0, access.mapper(FeishuManagementConnectionMapper.class).selectCount(null));
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertTrue(migration.validateWithResult().validationSuccessful);
        }
    }

    @Test
    void upgradeSeparatesDedicatedBotsWithoutDisablingExistingCompanyCapabilities() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.4.0.4").cleanDisabled(true).load().migrate();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            String agent = agent(access, enterprise, owner);
            String standalone = connection(access, enterprise, owner, "feishu", false, false);
            String dedicated = connection(access, enterprise, owner, "feishu", false, false);
            String login = connection(access, enterprise, owner, "feishu", true, false);
            String notification = connection(access, enterprise, owner, "feishu", false, true);
            String removed = connection(access, enterprise, owner, "feishu", false, false);
            String wecom = connection(access, enterprise, owner, "wecom_aibot", false, false);
            bot(access, enterprise, agent, dedicated, "enabled");
            bot(access, enterprise, agent, login, "enabled");
            bot(access, enterprise, agent, notification, "disabled");
            bot(access, enterprise, agent, removed, "deleted");

            var migration = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .cleanDisabled(true).validateMigrationNaming(true).load();
            // 后续版本可以继续增加脚本，这里验证本次待执行的脚本全部完成。
            int pendingMigrations = migration.info().pending().length;
            assertTrue(pendingMigrations > 0);
            assertEquals(pendingMigrations, migration.migrate().migrationsExecuted);
            var connections = access.mapper(EnterpriseIntegrationMapper.class);
            assertEquals("enterprise", connections.selectById(standalone).getPurpose());
            assertEquals("chat_bot", connections.selectById(dedicated).getPurpose());
            assertEquals("enterprise", connections.selectById(login).getPurpose());
            assertTrue(connections.selectById(login).getLoginEnabled());
            assertEquals("enterprise", connections.selectById(notification).getPurpose());
            assertTrue(connections.selectById(notification).getMessagingEnabled());
            assertEquals("enterprise", connections.selectById(removed).getPurpose());
            assertEquals("chat_bot", connections.selectById(wecom).getPurpose());
            var loginError = assertThrows(DataAccessException.class, () -> connections.update(
                new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, dedicated)
                    .set(EnterpriseIntegrationRow::getLoginEnabled, true)));
            assertEquals(3819, assertInstanceOf(SQLException.class, loginError.getMostSpecificCause()).getErrorCode());
            var messagingError = assertThrows(DataAccessException.class, () -> connections.update(
                new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, dedicated)
                    .set(EnterpriseIntegrationRow::getMessagingEnabled, true)));
            assertEquals(3819, assertInstanceOf(SQLException.class, messagingError.getMostSpecificCause()).getErrorCode());
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertTrue(migration.validateWithResult().validationSuccessful);
        }
    }

    private String user(MybatisTestDatabase access) {
        var row = new AppUserRow();
        row.setId(UUID.randomUUID().toString());
        row.setUsername("purpose_" + row.getId());
        row.setUsernameNormalized(row.getUsername());
        row.setPasswordHash("isolated-schema-password-hash");
        row.setDisplayName("接入用途测试成员");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        access.mapper(AppUserTableMapper.class).insert(row);
        return row.getId();
    }

    private String enterprise(MybatisTestDatabase access, String owner) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("接入用途升级测试企业");
        row.setCreatedBy(owner);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setQuotaPeriodStart(Instant.now());
        row.setQuotaPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        access.mapper(EnterpriseTableMapper.class).insert(row);
        return row.getId();
    }

    private String connection(MybatisTestDatabase access, String enterprise, String owner, String provider, boolean login, boolean messaging) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode(provider);
        row.setName("升级前接入");
        row.setExternalAppId("app_" + row.getId());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setIdentityScope(provider.equals("wecom_aibot") ? "bot" : "tenant");
        row.setLoginEnabled(login);
        row.setMessagingEnabled(messaging);
        row.setCreatedBy(owner);
        row.setUpdatedBy(owner);
        access.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return row.getId();
    }

    private String agent(MybatisTestDatabase access, String enterprise, String owner) {
        var member = new EnterpriseMemberRow();
        member.setEnterpriseId(enterprise);
        member.setUserId(owner);
        member.setDisplayName("升级测试成员");
        member.setJoinedAt(Instant.now());
        member.setUpdatedAt(Instant.now());
        access.mapper(IdentityQueryMapper.class).insert(member);
        var row = new ResourceRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setOwnerUserId(owner);
        row.setKind("agent");
        row.setName("升级前智能体");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        access.mapper(ResourceSqlMapper.class).insert(row);
        return row.getId();
    }

    private void bot(MybatisTestDatabase access, String enterprise, String agent, String connection, String status) {
        var row = new ChatBotBindingRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setConnectionId(connection);
        row.setPlatformBotId("ou_" + row.getId());
        row.setAgentId(agent);
        row.setRoleName("升级前岗位");
        row.setRoleDescription("仅验证旧接入分类");
        row.setStatus(status);
        access.mapper(ChatBotBindingMapper.class).insert(row);
    }
}
