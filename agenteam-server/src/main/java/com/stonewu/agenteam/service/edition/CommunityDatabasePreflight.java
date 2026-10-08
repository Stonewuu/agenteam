package com.stonewu.agenteam.service.edition;

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

/** 在任何自动迁移之前只读检查；旧完整库及商业库不能被当作社区库继续修改。 */
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
                throw rejected("此数据库没有社区版部署记录。现有完整版本数据请使用商业版迁移；若另一节点正在首次初始化数据库，请等其完成后重启。");
            }
            if (!tables.containsAll(Set.of("enterprise", "app_user", "system_super_admin_lock"))) {
                throw rejected("数据库缺少必要的企业或初始化记录，请先恢复完整备份。");
            }
            validate(mapper.snapshot());
        }
    }

    private void validate(InstallationPreflightRow state) {
        if (state == null || state.getRecordCount() != 1 || state.getEdition() == null) {
            throw rejected("部署记录不完整，请恢复原部署记录，不能重新选择初始化企业。");
        }
        if (!state.getEdition().equals("community")) {
            throw rejected("此数据库属于商业版，请使用商业版程序启动；社区版不会修改已有数据。");
        }
        if (state.getInstallationId() == null) {
            if (state.getEnterpriseCount() == 0 && state.getAdministratorCount() == 0 && state.getUserCount() == 0
                && state.getInitialEnterpriseId() == null) {
                return;
            }
            throw rejected("数据库已经包含业务数据，但缺少完整的初始化部署关系，请使用商业版迁移或恢复备份。");
        }
        if (!state.getInstallationId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw rejected("保存的部署编号格式不正确，请恢复原部署记录。");
        }
        if (state.getEnterpriseCount() != 1 || state.getAdministratorCount() != 1
            || state.getInitialEnterpriseId() == null || state.getInitialEnterpriseCount() != 1) {
            throw rejected("社区版只支持初始化时建立的企业，当前数据库的企业或初始化记录不一致。");
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
            LOG.error("启动前无法建立部署检查所需的数据库映射", failure);
            throw new IllegalStateException("无法检查数据库所属版本，应用启动已停止。", failure);
        }
    }

    private IllegalStateException rejected(String reason) {
        return new IllegalStateException(reason);
    }
}
