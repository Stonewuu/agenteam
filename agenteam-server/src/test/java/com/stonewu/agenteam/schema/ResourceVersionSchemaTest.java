package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.agent.AgentHireApplicationSqlMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.AuthSqlMapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.auth.SystemSuperAdminLockMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseSqlMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTeamTableMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamSqlMapper;
import com.stonewu.agenteam.mapper.permission.MemberRoleQueryMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.test.agent.AgentHireFixtureMapper;
import com.stonewu.agenteam.mapper.test.agent.AgentHireRequestFixtureMapper;
import com.stonewu.agenteam.mapper.test.enterprise.ResourceFixtureMapper;
import com.stonewu.agenteam.mapper.test.resource.ResourceDependencyFixtureMapper;
import com.stonewu.agenteam.mapper.test.resource.ResourceVersionFixtureMapper;
import com.stonewu.agenteam.mapper.usage.QuotaPolicySqlMapper;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRequestRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.support.DataAccessUtils;

import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ResourceVersionSchemaTest {

    @Test
    void rejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHires() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            MybatisTestDatabase databaseAccess = database.databaseAccess();
            AuthMapper users = new AuthMapper(databaseAccess.mapper(AppUserTableMapper.class), databaseAccess.mapper(UserPreferenceMapper.class), databaseAccess.mapper(IdentityQueryMapper.class), databaseAccess.mapper(SystemSuperAdminLockMapper.class), databaseAccess.mapper(AuthSqlMapper.class));
            users.insertUser("owner", "resource_owner", "test-stored-hash", "资源维护者", false, Instant.now());
            for (String enterprise : new String[]{"first", "second"}) {
                new EnterpriseMapper(databaseAccess.mapper(EnterpriseSqlMapper.class), databaseAccess.mapper(QuotaPolicySqlMapper.class), databaseAccess.mapper(IdentityQueryMapper.class), databaseAccess.mapper(EnterpriseTeamTableMapper.class), databaseAccess.mapper(MemberTeamSqlMapper.class), databaseAccess.mapper(MemberRoleQueryMapper.class)).insert(enterprise, enterprise, "", null, "owner", QuotaPeriod.containing(Instant.now(), "Asia/Shanghai"), Instant.now());
                users.addMember(enterprise, "owner", "资源维护者", Instant.now());
                databaseAccess.mapper(ResourceFixtureMapper.class).resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate(enterprise, enterprise);
            }
            database.initialize();
            assertEquals("资源草稿", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getName).eq(ResourceRow::getId, "first")).stream().map(fixtureRecord -> fixtureRecord.getName()).toList()));
            version(databaseAccess, "first-v1", "first", "first", 1);
            version(databaseAccess, "second-v1", "second", "second", 1);
            databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getId, "first").set(ResourceRow::getPublishedVersionId, "first-v1"));
            assertThrows(DataIntegrityViolationException.class, () -> databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getId, "first").set(ResourceRow::getPublishedVersionId, "second-v1")));
            databaseAccess.mapper(ResourceFixtureMapper.class).resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate12();
            version(databaseAccess, "another-v1", "first", "another", 1);
            assertThrows(DataIntegrityViolationException.class, () -> databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getId, "first").set(ResourceRow::getPublishedVersionId, "another-v1")));
            assertThrows(DataIntegrityViolationException.class, () -> version(databaseAccess, "duplicate-v1", "first", "first", 1));
            assertThrows(DataIntegrityViolationException.class, () -> databaseAccess.mapper(ResourceDependencyFixtureMapper.class).resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate());
            databaseAccess.mapper(AgentHireFixtureMapper.class).resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate();
            assertThrows(DataIntegrityViolationException.class, () -> databaseAccess.mapper(AgentHireFixtureMapper.class).resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate6());
            application(databaseAccess, "pending", "pending", 1);
            assertThrows(DataIntegrityViolationException.class, () -> application(databaseAccess, "duplicate", "pending", 1));
            assertCheckConstraint(() -> application(databaseAccess, "invalid-null", "pending", null));
            assertCheckConstraint(() -> application(databaseAccess, "invalid-terminal", "rejected", 1));
            databaseAccess.mapper(AgentHireApplicationSqlMapper.class).update(new LambdaUpdateWrapper<AgentHireRequestRow>().eq(AgentHireRequestRow::getId, "pending").set(AgentHireRequestRow::getStatus, "withdrawn").set(AgentHireRequestRow::getPendingMarker, null));
            application(databaseAccess, "next-pending", "pending", 1);
            assertEquals(2, Math.toIntExact(databaseAccess.mapper(AgentHireApplicationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRequestRow>())));
        }
    }

    private void version(MybatisTestDatabase databaseAccess, String id, String enterprise, String resource, int number) {
        databaseAccess.mapper(ResourceVersionFixtureMapper.class).resourceVersionSchemaVersionUpdate(id, enterprise, resource, number, "0".repeat(64));
    }

    private void application(MybatisTestDatabase databaseAccess, String id, String status, Integer marker) {
        databaseAccess.mapper(AgentHireRequestFixtureMapper.class).createApplication(id, status, marker);
    }

    private void assertCheckConstraint(Runnable operation) {
        var failure = assertThrows(DataAccessException.class, operation::run);
        assertEquals(3819, assertInstanceOf(SQLException.class, failure.getMostSpecificCause()).getErrorCode());
    }
}
