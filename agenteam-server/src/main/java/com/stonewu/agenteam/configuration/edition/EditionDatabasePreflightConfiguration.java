package com.stonewu.agenteam.configuration.edition;

import com.stonewu.agenteam.model.edition.entity.ProductEdition;
import com.stonewu.agenteam.service.edition.CommunityDatabasePreflight;
import com.stonewu.agenteam.service.edition.EditionDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** 数据源交给迁移、业务映射或后台服务之前检查，关闭自动迁移也不能跳过版本检查。 */
@Configuration(proxyBeanMethods = false)
public class EditionDatabasePreflightConfiguration {
    @Bean
    static BeanPostProcessor installationDatabasePreflight(ObjectProvider<EditionDescriptor> editions) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource source && editions.getObject().edition() == ProductEdition.COMMUNITY) {
                    new CommunityDatabasePreflight().verify(source);
                }
                return bean;
            }
        };
    }
}
