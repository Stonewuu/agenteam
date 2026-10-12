package com.stonewu.agenteam.service.plugin;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.plugin.JavaPluginInstallationMapper;
import com.stonewu.agenteam.model.plugin.entity.JavaPluginInstallationRow;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Clock;
import java.time.Duration;

/**
 * 未确认安装的程序包只保留一天，清理时再次限定待安装状态。
 */
@Component
@ConditionalOnProperty(name = "java-plugins.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class JavaPluginPreparationCleanup {
    private final JavaPluginInstallationMapper installations;
    private final Clock clock;

    public JavaPluginPreparationCleanup(JavaPluginInstallationMapper installations, Clock clock) {
        this.installations = installations;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void clean() {
        installations.update(null, new LambdaUpdateWrapper<JavaPluginInstallationRow>()
            .eq(JavaPluginInstallationRow::getStatus, "prepared")
            .lt(JavaPluginInstallationRow::getCreatedAt, clock.instant().minus(Duration.ofDays(1)))
            .set(JavaPluginInstallationRow::getArtifact, null).set(JavaPluginInstallationRow::getStatus, "uninstalled")
            .set(JavaPluginInstallationRow::getUpdatedAt, clock.instant()));
    }
}
