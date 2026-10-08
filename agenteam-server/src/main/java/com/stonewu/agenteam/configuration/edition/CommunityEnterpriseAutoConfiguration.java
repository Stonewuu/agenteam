package com.stonewu.agenteam.configuration.edition;

import com.stonewu.agenteam.service.edition.EnterpriseEditionPolicy;
import com.stonewu.agenteam.service.edition.InstallationService;
import com.stonewu.agenteam.service.edition.SingleEnterprisePolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = CommunityEditionAutoConfiguration.class)
public class CommunityEnterpriseAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(EnterpriseEditionPolicy.class)
    public EnterpriseEditionPolicy communityEnterprisePolicy(InstallationService installation) {
        return new SingleEnterprisePolicy(installation);
    }
}
