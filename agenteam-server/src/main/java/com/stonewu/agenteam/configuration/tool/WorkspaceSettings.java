package com.stonewu.agenteam.configuration.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工作文件容量和容器限制由部署配置确定，模型参数不能扩大这些范围。
 */
@Component
public record WorkspaceSettings(String root, String image, boolean executeEnabled, int workspaceMb, int fileMb,
                                int maximumFiles, int memoryMb, int cpus, int processLimit, int timeoutSeconds,
                                String network) {

    public WorkspaceSettings(@Value("${execution.workspace-files-root:.agenteam/workspace-files}") String root,
                             @Value("${execution.sandbox.image:agenteam/sandbox-office:local}") String image,
                             @Value("${execution.sandbox.enabled:true}") boolean executeEnabled,
                             @Value("${execution.sandbox.workspace-mb:128}") int workspaceMb,
                             @Value("${execution.sandbox.file-mb:20}") int fileMb,
                             @Value("${execution.sandbox.maximum-files:2000}") int maximumFiles,
                             @Value("${execution.sandbox.memory-mb:512}") int memoryMb,
                             @Value("${execution.sandbox.cpus:1}") int cpus,
                             @Value("${execution.sandbox.process-limit:64}") int processLimit,
                             @Value("${execution.sandbox.timeout-seconds:1800}") int timeoutSeconds,
                             @Value("${execution.sandbox.network:none}") String network) {
        if (root == null || root.isBlank() || root.contains(
            ",") || image == null || image.isBlank() || workspaceMb < 16 || workspaceMb > 1024 || fileMb < 1 || fileMb > 100 || fileMb > workspaceMb || maximumFiles < 10 || maximumFiles > 10000 || memoryMb < workspaceMb + 128 || memoryMb > 8192 || cpus < 1 || cpus > 8 || processLimit < 16 || processLimit > 512 || timeoutSeconds < 1 || timeoutSeconds > 86400 || network == null || !network.matches(
            "[A-Za-z0-9_-]{1,100}") || network.equals("host")) {
            throw new IllegalArgumentException("工作空间或容器配置超出允许范围");
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
    }

    public long workspaceBytes() {
        return workspaceMb * 1024L * 1024;
    }

    public int fileBytes() {
        return fileMb * 1024 * 1024;
    }
}
