package com.stonewu.agenteam.mapper.data.mysql;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.configuration.persistence.DataQueryControlInterceptor;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.IOException;
import java.sql.Connection;

/**
 * 映射定义只加载一次；连接由外部只读连接服务逐次创建，绝不借用平台连接。
 */
public final class ExternalMysqlSessions {
    private static final SqlSessionFactory FACTORY = createFactory();

    private ExternalMysqlSessions() {
    }

    public static SqlSession open(Connection connection) {
        return FACTORY.openSession(connection);
    }

    private static SqlSessionFactory createFactory() {
        var configuration = new Configuration(
            new Environment("external-mysql", new JdbcTransactionFactory(), new UnpooledDataSource()));
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.setReturnInstanceForEmptyRow(true);
        configuration.addInterceptor(new DataQueryControlInterceptor());
        String resource = "mapper-external/mysql/ExternalMysqlSqlMapper.xml";
        try (var input = ExternalMysqlSessions.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(Messages.text("error.data.externalMysqlSessions.createFactory.1"));
            }
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (IOException failure) {
            throw new IllegalStateException(Messages.text("error.data.externalMysqlSessions.createFactory.2"), failure);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
