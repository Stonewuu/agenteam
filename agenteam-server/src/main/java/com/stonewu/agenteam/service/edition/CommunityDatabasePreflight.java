package com.stonewu.agenteam.service.edition;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.stonewu.agenteam.configuration.persistence.MybatisPersistenceConfiguration;
import com.stonewu.agenteam.mapper.edition.InstallationPreflightMapper;
import com.stonewu.agenteam.model.edition.entity.InstallationPreflightRow;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.util.Set;

/** 在自动迁移之前只读检查，避免修改与当前安装包不兼容的数据库。 */
public final class CommunityDatabasePreflight {
    private static final Logger LOG = LoggerFactory.getLogger(CommunityDatabasePreflight.class);

    public void verify(DataSource dataSource) {
        try (var session = factory(dataSource).openSession()) {
            var mapper = session.getMapper(InstallationPreflightMapper.class);
            var tables = Set.copyOf(mapper.tableNames());
            if (tables.isEmpty()) {
                return;
            }
            if (!tables.contains("agenteam_installation")) {
                throw rejected(Messages.text("error.edition.communityDatabasePreflight.verify.1"));
            }
            if (!tables.containsAll(Set.of("enterprise", "app_user", "system_super_admin_lock"))) {
                throw rejected(Messages.text("error.edition.communityDatabasePreflight.verify.2"));
            }
            validate(mapper.snapshot());
        }
    }

    private void validate(InstallationPreflightRow state) {
        if (state == null || state.getRecordCount() != 1 || state.getEdition() == null) {
            throw rejected(Messages.text("error.edition.communityDatabasePreflight.validate.1"));
        }
        if (!state.getEdition().equals("community")) {
            throw rejected(Messages.text("error.edition.communityDatabasePreflight.validate.2"));
        }
        if (state.getInstallationId() == null) {
            if (state.getEnterpriseCount() == 0 && state.getAdministratorCount() == 0 && state.getUserCount() == 0
                && state.getInitialEnterpriseId() == null) {
                return;
            }
            throw rejected(Messages.text("error.edition.communityDatabasePreflight.validate.3"));
        }
        if (!state.getInstallationId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw rejected(Messages.text("error.edition.communityDatabasePreflight.validate.4"));
        }
        if (state.getEnterpriseCount() != 1 || state.getAdministratorCount() != 1
            || state.getInitialEnterpriseId() == null || state.getInitialEnterpriseCount() != 1) {
            throw rejected(Messages.text("error.edition.communityDatabasePreflight.validate.5"));
        }
    }

    private SqlSessionFactory factory(DataSource source) {
        try {
            var configuration = new MybatisConfiguration();
            MybatisPersistenceConfiguration.configure(configuration);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setTransactionFactory(new JdbcTransactionFactory());
            factory.setConfiguration(configuration);
            factory.setGlobalConfig(new GlobalConfig().setBanner(false).setDbConfig(new GlobalConfig.DbConfig()));
            factory.setMapperLocations(new ClassPathResource("mapper/edition/InstallationPreflightMapper.xml"));
            return factory.getObject();
        } catch (Exception failure) {
            LOG.error(Messages.log("log.edition.communityDatabasePreflight.factory.1"), failure);
            throw new IllegalStateException(Messages.text("error.edition.communityDatabasePreflight.factory.1"), failure);
        }
    }

    private IllegalStateException rejected(String reason) {
        return new IllegalStateException(reason);
    }
}
