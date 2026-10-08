package com.stonewu.agenteam.configuration.edition;

import com.stonewu.agenteam.model.edition.entity.ProductEdition;
import com.stonewu.agenteam.service.edition.EditionDescriptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 没有安装其他发行组件时使用社区版；用户权限与许可验证由对应服务执行。 */
@AutoConfiguration
public class CommunityEditionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(EditionDescriptor.class)
    public EditionDescriptor communityEditionDescriptor() {
        return () -> ProductEdition.COMMUNITY;
    }
}
