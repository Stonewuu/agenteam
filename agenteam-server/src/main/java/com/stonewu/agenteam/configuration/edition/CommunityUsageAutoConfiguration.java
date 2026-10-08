package com.stonewu.agenteam.configuration.edition;

import com.stonewu.agenteam.mapper.usage.EnterpriseUsageMapper;
import com.stonewu.agenteam.service.usage.CommunityQuotaPolicyProvider;
import com.stonewu.agenteam.service.usage.QuotaPolicyProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CommunityUsageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(QuotaPolicyProvider.class)
    public QuotaPolicyProvider communityQuotaPolicyProvider(EnterpriseUsageMapper usage) {
        return new CommunityQuotaPolicyProvider(usage);
    }
}
