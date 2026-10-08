package com.stonewu.agenteam.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.github.yulichang.injector.MPJSqlInjector;
import com.github.yulichang.interceptor.MPJInterceptor;
import com.stonewu.agenteam.configuration.persistence.DataQueryControlInterceptor;
import com.stonewu.agenteam.configuration.persistence.MybatisPaginationConfiguration;
import com.stonewu.agenteam.configuration.persistence.MybatisPersistenceConfiguration;
import com.stonewu.agenteam.configuration.persistence.QueryTimeoutInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 测试数据与断言调用真实映射，不提供接收任意数据库语句的入口。
 */
@Component
public final class MybatisTestDatabase {
    private final SqlSessionTemplate session;
    private final SqlSession direct;
    private final SqlSessionFactory factory;

    @Autowired
    public MybatisTestDatabase(SqlSessionTemplate session) {
        this.session = session;
        this.factory = session.getSqlSessionFactory();
        this.direct = null;
    }

    public MybatisTestDatabase(DataSource dataSource) {
        this.factory = createFactory(dataSource);
        if (dataSource instanceof SingleConnectionDataSource single) {
            try {
                this.direct = factory.openSession(single.getConnection());
            } catch (SQLException failure) {
                throw new IllegalStateException("测试事务连接无法读取", failure);
            }
            this.session = null;
        } else {
            this.session = new SqlSessionTemplate(factory);
            this.direct = null;
        }
    }

    public <T> T mapper(Class<T> type) {
        return direct == null ? session.getMapper(type) : direct.getMapper(type);
    }

    public DataSource getDataSource() {
        return factory.getConfiguration().getEnvironment().getDataSource();
    }

    public SqlSessionFactory sessionFactory() {
        return factory;
    }

    public SqlSessionTemplate sessionTemplate() {
        if (session == null) {
            throw new IllegalStateException("单连接映射只能用于调用方管理的测试事务");
        }
        return session;
    }

    public String catalog() {
        var connection = DataSourceUtils.getConnection(getDataSource());
        try {
            return connection.getCatalog();
        } catch (SQLException failure) {
            throw new IllegalStateException("测试数据库名称无法读取", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, getDataSource());
        }
    }

    private static SqlSessionFactory createFactory(DataSource source) {
        try {
            var configuration = new MybatisConfiguration();
            MybatisPersistenceConfiguration.configure(configuration);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            if (source instanceof SingleConnectionDataSource) {
                factory.setTransactionFactory(new JdbcTransactionFactory());
            }
            factory.setConfiguration(configuration);
            factory.setGlobalConfig(new GlobalConfig().setBanner(false).setDbConfig(new GlobalConfig.DbConfig()).setSqlInjector(new MPJSqlInjector()));
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:/mapper/**/*.xml"));
            factory.setPlugins(new MybatisPaginationConfiguration().mybatisPlusInterceptor(),
                new QueryTimeoutInterceptor(), new DataQueryControlInterceptor(), new MPJInterceptor());
            factory.afterPropertiesSet();
            SqlSessionFactory result = factory.getObject();
            var resolver = new ResolverUtil<Object>();
            resolver.find(new ResolverUtil.IsA(Object.class), "com.stonewu.agenteam.mapper");
            for (Class<?> type : resolver.getClasses()) {
                if (type.isInterface() && type.isAnnotationPresent(Mapper.class) && !result.getConfiguration().hasMapper(type)) {
                    result.getConfiguration().addMapper(type);
                }
            }
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("测试数据库映射无法初始化", failure);
        }
    }
}
