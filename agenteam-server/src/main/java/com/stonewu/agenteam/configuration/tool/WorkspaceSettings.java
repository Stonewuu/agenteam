package com.stonewu.agenteam.configuration.tool;

import com.stonewu.agenteam.service.i18n.Messages;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.stonewu.agenteam.service.workspace.WorkspaceLimitsStore;

/**
 * 工作文件限制优先使用管理员保存的配置，容器资源仍由部署配置确定。
 */
@Component
public record WorkspaceSettings(String root, String image, boolean executeEnabled, int workspaceMb, int fileMb,
                                int maximumFiles, int memoryMb, int cpus, int processLimit, int timeoutSeconds,
                                String network, WorkspaceLimitsStore limits) {

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
                             @Value("${execution.sandbox.network:none}") String network, WorkspaceLimitsStore limits) {
        if (root == null || root.isBlank() || root.contains(
            ",") || image == null || image.isBlank() || workspaceMb < 16 || fileMb < 1 || fileMb > workspaceMb || maximumFiles < 10 || memoryMb < 128 || memoryMb > 8192 || cpus < 1 || cpus > 8 || processLimit < 16 || processLimit > 512 || timeoutSeconds < 1 || timeoutSeconds > 86400 || network == null || !network.matches(
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

    public long workspaceBytes() {
        return workspaceMb() * 1024L * 1024;
    }

    public long fileBytes() {
        return fileMb() * 1024L * 1024;
    }
}
