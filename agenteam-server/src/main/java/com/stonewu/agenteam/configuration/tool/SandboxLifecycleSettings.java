package com.stonewu.agenteam.configuration.tool;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.entity.LimitValue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 空闲期限和运行环境数量可由管理员调整，命令使用独立的执行超时。
 */
@Component
public record SandboxLifecycleSettings(int idleSeconds, int scanSeconds, int staleSeconds, int stopSeconds,
                                       int maximumRunning, RuntimeSettingsStore runtimeSettings) {

    public SandboxLifecycleSettings(int idleSeconds, int scanSeconds, int staleSeconds, int stopSeconds) {
        this(idleSeconds, scanSeconds, staleSeconds, stopSeconds, 4);
    }

    @Autowired
    public SandboxLifecycleSettings(@Value("${execution.sandbox.idle-seconds:1800}") int idleSeconds,
                                    @Value("${execution.sandbox.idle-scan-seconds:30}") int scanSeconds,
                                    @Value("${execution.sandbox.usage-stale-seconds:60}") int staleSeconds,
                                    @Value("${execution.sandbox.stop-seconds:10}") int stopSeconds,
                                    @Value("${execution.sandbox.maximum-running-containers:4}") int maximumRunning, RuntimeSettingsStore runtimeSettings) {
        if (!LimitValue.validInteger(idleSeconds, 1) || scanSeconds < 1 || staleSeconds < 10 || stopSeconds < 1 || stopSeconds > 60 || !LimitValue.validInteger(maximumRunning, 1)) {
            throw new IllegalArgumentException(Messages.text("error.tool.sandboxLifecycleSettings.init.1"));
        }
        this.idleSeconds = idleSeconds;
        this.scanSeconds = scanSeconds;
        this.staleSeconds = staleSeconds;
        this.stopSeconds = stopSeconds;
        this.maximumRunning = maximumRunning;
        this.runtimeSettings = runtimeSettings;
    }

    public SandboxLifecycleSettings(int idleSeconds, int scanSeconds, int staleSeconds, int stopSeconds,
                                    int maximumRunning) {
        this(idleSeconds, scanSeconds, staleSeconds, stopSeconds, maximumRunning, null);
    }

    public int idleSeconds() {
        return runtimeSettings == null ? idleSeconds : Math.toIntExact(runtimeSettings.value(RuntimeSetting.SANDBOX_IDLE_SECONDS, idleSeconds));
    }

    public int maximumRunning() {
        return runtimeSettings == null ? maximumRunning : Math.toIntExact(runtimeSettings.value(RuntimeSetting.SANDBOX_RUNNING, maximumRunning));
    }

}
