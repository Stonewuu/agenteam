package com.stonewu.agenteam.support;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 手工构造的测试服务容器也使用与生产一致的映射实例。
 */
@TestConfiguration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.stonewu.agenteam.mapper", annotationClass = Mapper.class)
public class TestMybatisConfiguration {
    @Bean
    public SqlSessionFactory sqlSessionFactory(MybatisTestDatabase database) {
        return database.sessionFactory();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(MybatisTestDatabase database) {
        return database.sessionTemplate();
    }
}
