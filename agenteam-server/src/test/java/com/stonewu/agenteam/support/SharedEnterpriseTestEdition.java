package com.stonewu.agenteam.support;

import com.stonewu.agenteam.model.edition.entity.ProductEdition;
import com.stonewu.agenteam.service.edition.EditionDescriptor;
import com.stonewu.agenteam.service.edition.EnterpriseEditionPolicy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 仅由共有业务的多企业契约测试显式导入，模拟上层发行组件。
 * 保留全部会话、成员和资源权限检查；不用于证明社区创建能力或商业许可证行为。
 */
@TestConfiguration(proxyBeanMethods = false)
public class SharedEnterpriseTestEdition {
    @Bean
    EditionDescriptor sharedBusinessTestEdition() {
        return () -> ProductEdition.PRO;
    }

    @Bean
    EnterpriseEditionPolicy sharedBusinessTestEnterprisePolicy() {
        return new EnterpriseEditionPolicy() {
            @Override
            public void requireAdditionalCreation() {
                // 共有业务的隔离测试需要准备多个企业，创建权限仍由生产服务检查。
            }

            @Override
            public void requireEnterpriseScope(String enterpriseId) {
                // 此类测试检查多企业共有业务，真实社区范围由社区接口测试单独验证。
            }
        };
    }
}
