package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationSecretMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.permission.SysRolePermissionTableMapper;
import com.stonewu.agenteam.mapper.permission.SysRoleTableMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.mapper.agent.AgentHireSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduledNotificationTargetMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationSecretRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.model.permission.entity.SysRolePermissionRow;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledNotificationTargetRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在真实隔离数据库验证旧企业升级、新身份唯一性、企业外键及重复启动。
 */
class IntegrationSchemaMigrationTest {
    private static final Set<String> TABLES = Set.of("enterprise_integration", "enterprise_integration_secret",
        "user_channel_binding", "channel_oauth_session", "user_channel_preference");

    @Test
    void upgradesExistingCompaniesAndOnlyGrantsNewPermissionsToBuiltinAdministrators() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            var base = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0").cleanDisabled(true).load();
            assertEquals(1, base.migrate().migrationsExecuted);
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            String administrator = role(access, enterprise, "enterprise-admin", true);
            String custom = role(access, enterprise, "custom-admin", false);
            var upgraded = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.0.1").cleanDisabled(true).validateMigrationNaming(true).load();
            assertEquals(1, upgraded.migrate().migrationsExecuted);
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables().containsAll(TABLES));
            assertEquals(6, access.mapper(SysRolePermissionTableMapper.class).selectCount(
                new LambdaQueryWrapper<SysRolePermissionRow>().eq(SysRolePermissionRow::getEnterpriseId, enterprise)
                    .eq(SysRolePermissionRow::getRoleId, administrator)));
            assertEquals(0, access.mapper(SysRolePermissionTableMapper.class).selectCount(
                new LambdaQueryWrapper<SysRolePermissionRow>().eq(SysRolePermissionRow::getRoleId, custom)));
            assertEquals(2L, access.mapper(EnterpriseTableMapper.class).selectById(enterprise).getPermissionVersion());
            assertEquals(0, upgraded.migrate().migrationsExecuted);
            var restarted = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.0.1").cleanDisabled(true).load();
            assertEquals(0, restarted.migrate().migrationsExecuted);
            assertTrue(restarted.validateWithResult().validationSuccessful);
        }
    }

    @Test
    void currentBindingsAreUniqueAndCannotCrossEnterpriseOrChangeHistory() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String firstUser = user(access), secondUser = user(access);
            String firstEnterprise = enterprise(access, firstUser), secondEnterprise = enterprise(access, secondUser);
            member(access, firstEnterprise, firstUser);
            member(access, firstEnterprise, secondUser);
            member(access, secondEnterprise, secondUser);
            String connection = connection(access, firstEnterprise, firstUser);
            var bindings = access.mapper(UserChannelBindingMapper.class);
            var original = binding(firstEnterprise, connection, firstUser, "ou_first");
            bindings.insert(original);
            assertThrows(DataIntegrityViolationException.class,
                () -> bindings.insert(binding(firstEnterprise, connection, firstUser, "ou_another")));
            assertThrows(DataIntegrityViolationException.class,
                () -> bindings.insert(binding(firstEnterprise, connection, secondUser, "ou_first")));
            assertThrows(DataIntegrityViolationException.class,
                () -> bindings.insert(binding(secondEnterprise, connection, secondUser, "ou_first")));
            bindings.update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, original.getId())
                .set(UserChannelBindingRow::getStatus, "disabled"));
            assertThrows(DataIntegrityViolationException.class,
                () -> bindings.insert(binding(firstEnterprise, connection, secondUser, "ou_first")));
            bindings.update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, original.getId())
                .set(UserChannelBindingRow::getStatus, "revoked").set(UserChannelBindingRow::getRevokedAt, Instant.now()));
            var rebound = binding(firstEnterprise, connection, firstUser, "ou_first");
            bindings.insert(rebound);
            assertNotEquals(original.getId(), rebound.getId());
            assertEquals("revoked", bindings.selectById(original.getId()).getStatus());
            assertEquals("active", bindings.selectById(rebound.getId()).getStatus());
        }
    }

    @Test
    void nonMemberGlobalAdministratorCanMaintainSecretsButUnverifiedApplicationsCannotBeEnabled() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String creator = user(access), globalAdministrator = user(access);
            String enterprise = enterprise(access, creator);
            String connection = connection(access, enterprise, globalAdministrator);
            var secret = new EnterpriseIntegrationSecretRow();
            secret.setId(UUID.randomUUID().toString());
            secret.setEnterpriseId(enterprise);
            secret.setConnectionId(connection);
            secret.setSecretName("app_secret");
            secret.setEncryptedValue("{\"keyVersion\":\"test\",\"nonce\":\"encrypted-test\",\"ciphertext\":\"encrypted-test\"}");
            secret.setUpdatedBy(globalAdministrator);
            access.mapper(EnterpriseIntegrationSecretMapper.class).insert(secret);
            assertEquals(globalAdministrator, access.mapper(EnterpriseIntegrationSecretMapper.class).selectById(secret.getId()).getUpdatedBy());
            var rejected = assertThrows(DataAccessException.class, () -> access.mapper(EnterpriseIntegrationMapper.class)
                .update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, connection)
                    .set(EnterpriseIntegrationRow::getStatus, "enabled")));
            var cause = assertInstanceOf(SQLException.class, rejected.getMostSpecificCause());
            assertEquals(3819, cause.getErrorCode());
            assertEquals("draft", access.mapper(EnterpriseIntegrationMapper.class).selectById(connection).getStatus());
        }
    }

    @Test
    void notificationUpgradePreservesHistoryAndConstrainsTheActualRecipient() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.0.1").cleanDisabled(true).load().migrate();
            String owner = user(access), other = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            member(access, enterprise, other);
            var notice = new NotificationRow();
            notice.setId(UUID.randomUUID().toString());
            notice.setEnterpriseId(enterprise);
            notice.setUserId(owner);
            notice.setSequenceNo(1L);
            notice.setEventKey("before-channel-upgrade");
            notice.setCategory("todo");
            notice.setTitle("升级前的通知");
            notice.setBody("保留已有内容");
            notice.setCreatedAt(Instant.now());
            access.mapper(NotificationSqlMapper.class).insert(notice);
            var latest = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.1.1").cleanDisabled(true).validateMigrationNaming(true).load();
            assertEquals(1, latest.migrate().migrationsExecuted);
            var preserved = access.mapper(NotificationSqlMapper.class).selectById(notice.getId());
            assertEquals("保留已有内容", preserved.getBody());
            assertNull(preserved.getSourceOccurrenceId());
            assertNull(preserved.getInitiatorUserId());
            String connection = connection(access, enterprise, owner);
            var mapper = access.mapper(NotificationChannelDeliveryMapper.class);
            var delivery = new NotificationChannelDeliveryRow();
            delivery.setId(UUID.randomUUID().toString());
            delivery.setEnterpriseId(enterprise);
            delivery.setNotificationId(notice.getId());
            delivery.setRecipientUserId(owner);
            delivery.setConnectionId(connection);
            delivery.setCredentialRevision(1L);
            delivery.setDeliveryReason("automatic");
            delivery.setProviderRequestId(UUID.randomUUID().toString());
            delivery.setStatus("blocked");
            delivery.setExpiresAt(Instant.now().plusSeconds(3600));
            mapper.insert(delivery);
            delivery.setId(UUID.randomUUID().toString());
            delivery.setProviderRequestId(UUID.randomUUID().toString());
            assertThrows(DataIntegrityViolationException.class, () -> mapper.insert(delivery));
            delivery.setConnectionId(connection(access, enterprise, owner));
            delivery.setRecipientUserId(other);
            assertThrows(DataIntegrityViolationException.class, () -> mapper.insert(delivery));
            assertEquals(1, mapper.selectCount(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
                .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)));
            assertEquals(0, latest.migrate().migrationsExecuted);
            assertTrue(latest.validateWithResult().validationSuccessful);
        }
    }

    private String user(MybatisTestDatabase database) {
        var row = new AppUserRow();
        row.setId(UUID.randomUUID().toString());
        row.setUsername("channel_" + row.getId());
        row.setUsernameNormalized(row.getUsername());
        row.setPasswordHash("isolated-schema-fixture");
        row.setDisplayName("接入结构测试用户");
        database.mapper(AppUserTableMapper.class).insert(row);
        return row.getId();
    }

    @Test
    void actionUpgradePreservesLegacySnapshotsAndAllowsNotificationsWithoutAnAgent() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.1.1").cleanDisabled(true).load().migrate();
            String owner = user(access), enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var plan = legacyPlan(access, enterprise, owner);
            var occurrence = new ScheduledOccurrenceRow();
            occurrence.setId(UUID.randomUUID().toString());
            occurrence.setEnterpriseId(enterprise);
            occurrence.setScheduleId(plan.getId());
            occurrence.setTriggerKind("scheduled");
            occurrence.setOccurrenceKey("old-scheduled-time");
            occurrence.setScheduledFor(Instant.now().minusSeconds(3600));
            occurrence.setStatus("missed");
            occurrence.setCreatedAt(Instant.now());
            occurrence.setUpdatedAt(Instant.now());
            access.mapper(ScheduleOccurrenceSqlMapper.class).insert(occurrence);
            var migration = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.1.1.2").cleanDisabled(true).validateMigrationNaming(true).load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            var plans = access.mapper(ScheduleSqlMapper.class);
            assertEquals("agent.run", plans.selectById(plan.getId()).getActionType());
            assertEquals("升级前固定输入", plans.selectById(plan.getId()).getInputText());
            var history = access.mapper(ScheduleOccurrenceSqlMapper.class).selectById(occurrence.getId());
            assertEquals("legacy_unavailable", history.getSnapshotOrigin());
            assertNull(history.getActionSnapshotJson());
            assertNull(history.getScheduleRevision());
            plan.setId(UUID.randomUUID().toString());
            plan.setHireId(null);
            plan.setAgentVersionId(null);
            plan.setInputText(null);
            plan.setActionType("notification.send");
            plan.setActionSchemaVersion(1);
            plan.setActionConfigJson("{\"title\":\"通知\",\"body\":\"内容\"}");
            plans.insert(plan);
            assertEquals(2, plans.listSchedules(enterprise, owner, "", null, 10).size());
            var loaded = plans.findScheduledTask(enterprise, owner, plan.getId(), false, true).getFirst();
            assertNull(loaded.getAgentVersionNo());
            assertEquals("notification.send", loaded.getActionType());
            var target = new ScheduledNotificationTargetRow();
            target.setId(UUID.randomUUID().toString());
            target.setEnterpriseId(enterprise);
            target.setScheduleId(plan.getId());
            target.setRecipientUserId(owner);
            var targets = access.mapper(ScheduledNotificationTargetMapper.class);
            targets.insert(target);
            assertEquals("in_app", targets.selectById(target.getId()).getChannelKey());
            target.setId(UUID.randomUUID().toString());
            assertThrows(DataIntegrityViolationException.class, () -> targets.insert(target));
            target.setEnterpriseId(enterprise(access, owner));
            member(access, target.getEnterpriseId(), owner);
            assertThrows(DataIntegrityViolationException.class, () -> targets.insert(target));
            assertThrows(DataAccessException.class, () -> plans.update(new LambdaUpdateWrapper<ScheduledTaskRow>()
                .eq(ScheduledTaskRow::getEnterpriseId, enterprise).eq(ScheduledTaskRow::getId, plan.getId())
                .set(ScheduledTaskRow::getActionType, "agent.run")));
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertTrue(migration.validateWithResult().validationSuccessful);
        }
    }

    private ScheduledTaskRow legacyPlan(MybatisTestDatabase database, String enterprise, String user) {
        var resource = new ResourceRow();
        resource.setId(UUID.randomUUID().toString());
        resource.setEnterpriseId(enterprise);
        resource.setOwnerUserId(user);
        resource.setKind("agent");
        resource.setName("升级前智能体");
        resource.setCreatedAt(Instant.now());
        resource.setUpdatedAt(Instant.now());
        database.mapper(ResourceSqlMapper.class).insert(resource);
        var version = new ResourceVersionRow();
        version.setId(UUID.randomUUID().toString());
        version.setEnterpriseId(enterprise);
        version.setResourceId(resource.getId());
        version.setVersionNo(1);
        version.setName(resource.getName());
        version.setConfigJson("{}");
        version.setConfigHash("0".repeat(64));
        version.setReleaseNote("迁移结构样本");
        version.setPublishedBy(user);
        version.setPublishedAt(Instant.now());
        database.mapper(ResourceVersionSqlMapper.class).insert(version);
        var hire = new AgentHireRow();
        hire.setId(UUID.randomUUID().toString());
        hire.setEnterpriseId(enterprise);
        hire.setUserId(user);
        hire.setAgentId(resource.getId());
        hire.setHiredAt(Instant.now());
        database.mapper(AgentHireSqlMapper.class).insert(hire);
        var plan = new ScheduledTaskRow();
        plan.setId(UUID.randomUUID().toString());
        plan.setEnterpriseId(enterprise);
        plan.setOwnerUserId(user);
        plan.setHireId(hire.getId());
        plan.setAgentVersionId(version.getId());
        plan.setName("升级前计划");
        plan.setInputText("升级前固定输入");
        plan.setFrequency("daily");
        plan.setLocalTime("09:00");
        plan.setWeekdaysJson("[]");
        plan.setTimezone("Asia/Shanghai");
        plan.setLastCheckedAt(Instant.now());
        database.mapper(ScheduleSqlMapper.class).insert(plan);
        return plan;
    }

    private String enterprise(MybatisTestDatabase database, String creator) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("接入结构测试企业");
        row.setCreatedBy(creator);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setQuotaPeriodStart(Instant.now());
        row.setQuotaPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        database.mapper(EnterpriseTableMapper.class).insert(row);
        return row.getId();
    }

    private void member(MybatisTestDatabase database, String enterprise, String user) {
        var row = new EnterpriseMemberRow();
        row.setEnterpriseId(enterprise);
        row.setUserId(user);
        row.setDisplayName("测试成员");
        row.setJoinedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        database.mapper(IdentityQueryMapper.class).insert(row);
    }

    private String role(MybatisTestDatabase database, String enterprise, String code, boolean builtin) {
        var row = new SysRoleRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setCode(code);
        row.setName(code);
        row.setNameKey(code);
        row.setBuiltin(builtin ? 1 : 0);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        database.mapper(SysRoleTableMapper.class).insert(row);
        return row.getId();
    }

    private String connection(MybatisTestDatabase database, String enterprise, String actor) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode("feishu");
        row.setName("飞书测试接入");
        row.setExternalAppId("cli_" + UUID.randomUUID());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        database.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return row.getId();
    }

    private UserChannelBindingRow binding(String enterprise, String connection, String user, String subject) {
        var row = new UserChannelBindingRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setConnectionId(connection);
        row.setUserId(user);
        row.setExternalSubjectType("feishu_open_id");
        row.setExternalSubjectId(subject);
        row.setAuthorizedAt(Instant.now());
        return row;
    }
}
