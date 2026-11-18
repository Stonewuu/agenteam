package com.stonewu.agenteam.configuration.tool;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.entity.LimitValue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.stonewu.agenteam.service.workspace.WorkspaceLimitsStore;

/**
 * 工作文件和环境资源优先使用管理员保存的配置。
 */
@Component
public record WorkspaceSettings(String root, String image, boolean executeEnabled, int workspaceMb, int fileMb,
                                int maximumFiles, int memoryMb, int cpus, int processLimit, int timeoutSeconds,
                                String network, WorkspaceLimitsStore limits, RuntimeSettingsStore runtimeSettings) {

    @Autowired
    public WorkspaceSettings(@Value("${execution.workspace-files-root:.agenteam/workspace-files}") String root,
                             @Value("${execution.sandbox.image:agenteam/sandbox-office:local}") String image,
                             @Value("${execution.sandbox.enabled:true}") boolean executeEnabled,
                             @Value("${execution.sandbox.workspace-mb:128}") int workspaceMb,
                             @Value("${execution.sandbox.file-mb:20}") int fileMb,
                             @Value("${execution.sandbox.maximum-files:2000}") int maximumFiles,
                             @Value("${execution.sandbox.memory-mb:512}") int memoryMb,
                             @Value("${execution.sandbox.cpus:1}") int cpus,
                             @Value("${execution.sandbox.process-limit:128}") int processLimit,
                             @Value("${execution.sandbox.timeout-seconds:1800}") int timeoutSeconds,
                             @Value("${execution.sandbox.network:none}") String network, WorkspaceLimitsStore limits, RuntimeSettingsStore runtimeSettings) {
        if (root == null || root.isBlank() || root.contains(
            ",") || image == null || image.isBlank() || !LimitValue.validInteger(workspaceMb, 16) || !LimitValue.validInteger(fileMb, 1) || !LimitValue.ordered(fileMb, workspaceMb) || !LimitValue.validInteger(maximumFiles, 10) || !LimitValue.validInteger(memoryMb, 128) || !LimitValue.validInteger(cpus, 1) || !LimitValue.validInteger(processLimit, 16) || !LimitValue.validInteger(timeoutSeconds, 1) || network == null || !network.matches(
            "[A-Za-z0-9_-]{1,100}") || network.equals("host")) {
            throw new IllegalArgumentException(Messages.text("error.tool.workspaceSettings.init.1"));
        }
        this.root = root;
        this.image = image;
        this.executeEnabled = executeEnabled;
        this.workspaceMb = workspaceMb;
        this.fileMb = fileMb;
        this.maximumFiles = maximumFiles;
        this.memoryMb = memoryMb;
        this.cpus = cpus;
        this.processLimit = processLimit;
        this.timeoutSeconds = timeoutSeconds;
        this.network = network;
        this.limits = limits;
        this.runtimeSettings = runtimeSettings;
    }

    public WorkspaceSettings(String root, String image, boolean executeEnabled, int workspaceMb, int fileMb,
                             int maximumFiles, int memoryMb, int cpus, int processLimit, int timeoutSeconds,
                             String network) {
        this(root, image, executeEnabled, workspaceMb, fileMb, maximumFiles, memoryMb, cpus, processLimit,
            timeoutSeconds, network, null);
    }

    public int workspaceMb() {
        var saved = limits == null ? null : limits.current();
        return saved == null ? workspaceMb : saved.limits().workspaceMb();
    }

    public int fileMb() {
        var saved = limits == null ? null : limits.current();
        return saved == null ? fileMb : saved.limits().fileMb();
    }

    public int maximumFiles() {
        var saved = limits == null ? null : limits.current();
        return saved == null ? maximumFiles : saved.limits().maximumFiles();
    }

    public int fileCountLimit() {
        return LimitValue.unlimited(maximumFiles()) ? Integer.MAX_VALUE : maximumFiles();
    }

    public long workspaceBytes() {
        return LimitValue.bytes(workspaceMb(), 1048576);
    }

    public long fileBytes() {
        return LimitValue.bytes(fileMb(), 1048576);
    }

    public WorkspaceSettings(String root, String image, boolean executeEnabled, int workspaceMb, int fileMb,
                             int maximumFiles, int memoryMb, int cpus, int processLimit, int timeoutSeconds,
                             String network, WorkspaceLimitsStore limits) {
        this(root, image, executeEnabled, workspaceMb, fileMb, maximumFiles, memoryMb, cpus, processLimit,
            timeoutSeconds, network, limits, null);
    }

    public int memoryMb() {
        return runtimeSettings == null ? memoryMb : Math.toIntExact(runtimeSettings.value(RuntimeSetting.SANDBOX_MEMORY_MB, memoryMb));
    }

    public int cpus() {
        return runtimeSettings == null ? cpus : Math.toIntExact(runtimeSettings.value(RuntimeSetting.SANDBOX_CPUS, cpus));
    }

    public int processLimit() {
        return runtimeSettings == null ? processLimit : Math.toIntExact(runtimeSettings.value(RuntimeSetting.SANDBOX_PROCESSES, processLimit));
    }

    public int timeoutSeconds() {
        return runtimeSettings == null ? timeoutSeconds : Math.toIntExact(runtimeSettings.value(RuntimeSetting.SANDBOX_TIMEOUT_SECONDS, timeoutSeconds));
    }

}
