package com.stonewu.agenteam.mapper.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import com.stonewu.agenteam.support.TestMybatisConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserCreationAtomicityTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
    }

    @Test
    void rollsBackUserWhenPreferenceCreationFails() throws Exception {
        try (var database = new IsolatedDatabase();
             var context = new AnnotationConfigApplicationContext()) {
            database.initialize();
            database.databaseAccess().mapper(TestDatabaseAdministrationMapper.class).rejectPreferenceCreation();
            context.register(Transactions.class);
            context.registerBean(DataSource.class, () -> database.databaseAccess().getDataSource());
            context.registerBean(MybatisTestDatabase.class, () -> new MybatisTestDatabase(context.getBean(DataSource.class)));
            context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(context.getBean(DataSource.class)));
            context.registerBean(AuthMapper.class);
            context.register(TestMybatisConfiguration.class);
            context.refresh();
            var failure = assertThrows(DataAccessException.class, () -> context.getBean(AuthMapper.class).insertUser("rejected-user", "rejected", "test-hash", "测试成员", false, Instant.now()));
            assertEquals(3819, ((SQLException) failure.getMostSpecificCause()).getErrorCode());
            assertEquals(0, Math.toIntExact(database.databaseAccess().mapper(AppUserTableMapper.class).selectCount(new LambdaQueryWrapper<AppUserRow>())));
        }
    }
}
