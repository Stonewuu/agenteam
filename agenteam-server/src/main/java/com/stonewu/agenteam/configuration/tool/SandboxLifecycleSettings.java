package com.stonewu.agenteam.configuration.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 空闲期限和检查间隔由部署决定，命令仍使用独立的执行超时。
 */
@Component
public record SandboxLifecycleSettings(int idleSeconds, int scanSeconds, int staleSeconds, int stopSeconds,
                                       int maximumRunning) {

    public SandboxLifecycleSettings(int idleSeconds, int scanSeconds, int staleSeconds, int stopSeconds) {
        this(idleSeconds, scanSeconds, staleSeconds, stopSeconds, 4);
    }

    @Autowired
    public SandboxLifecycleSettings(@Value("${execution.sandbox.idle-seconds:900}") int idleSeconds,
                                    @Value("${execution.sandbox.idle-scan-seconds:30}") int scanSeconds,
                                    @Value("${execution.sandbox.usage-stale-seconds:60}") int staleSeconds,
                                    @Value("${execution.sandbox.stop-seconds:10}") int stopSeconds,
                                    @Value("${execution.sandbox.maximum-running-containers:4}") int maximumRunning) {
        if (idleSeconds < 1 || scanSeconds < 1 || staleSeconds < 10 || stopSeconds < 1 || stopSeconds > 60 || maximumRunning < 1 || maximumRunning > 64) {
            throw new IllegalArgumentException("沙盒空闲回收时间配置不正确");
        }
        this.idleSeconds = idleSeconds;
        this.scanSeconds = scanSeconds;
        this.staleSeconds = staleSeconds;
        this.stopSeconds = stopSeconds;
        this.maximumRunning = maximumRunning;
    }
}
