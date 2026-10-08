package com.stonewu.agenteam.configuration.persistence;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.type.JdbcType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生产与测试共用查询语义，避免测试配置覆盖应用配置后改变空值或行锁读取行为。
 */
@Configuration(proxyBeanMethods = false)
public class MybatisPersistenceConfiguration {

    @Bean
    public ConfigurationCustomizer persistenceConfigurationCustomizer() {
        return MybatisPersistenceConfiguration::configure;
    }

    public static void configure(MybatisConfiguration configuration) {
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.setJdbcTypeForNull(JdbcType.NULL);
        configuration.setReturnInstanceForEmptyRow(true);
        configuration.setCallSettersOnNulls(false);
    }
}
