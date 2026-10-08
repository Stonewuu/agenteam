package com.stonewu.agenteam.configuration.edition;

import com.stonewu.agenteam.model.edition.entity.ProductEdition;
import com.stonewu.agenteam.service.edition.EditionDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityEditionAutoConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CommunityEditionAutoConfiguration.class));

    @Test
    void usesCommunityWhenNoOtherEditionIsInstalled() {
        context.run(application -> {
            assertThat(application).hasSingleBean(EditionDescriptor.class);
            assertThat(application.getBean(EditionDescriptor.class).edition()).isEqualTo(ProductEdition.COMMUNITY);
        });
    }

    @Test
    void anInstalledEditionReplacesTheDefaultWithoutBeanOverriding() {
        context.withBean(EditionDescriptor.class, () -> () -> ProductEdition.PRO).run(application -> {
            assertThat(application).hasSingleBean(EditionDescriptor.class);
            assertThat(application.getBean(EditionDescriptor.class).edition()).isEqualTo(ProductEdition.PRO);
        });
    }
}
