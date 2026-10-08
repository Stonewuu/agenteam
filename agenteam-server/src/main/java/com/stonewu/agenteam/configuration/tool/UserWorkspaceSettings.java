package com.stonewu.agenteam.configuration.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 用户空间的持久根目录和 Docker 主机路径映射只由部署配置提供。
 */
@Component
public record UserWorkspaceSettings(String root, String hostRoot) {

    public UserWorkspaceSettings(
        @Value("${execution.user-workspaces-root:${agenteam.storage.root:.agenteam}/user-workspaces}") String root,
        @Value("${execution.user-workspaces-host-root:}") String hostRoot) {
        if (root == null || root.isBlank() || hostRoot == null || root.contains(",") || hostRoot.contains(",")) {
            throw new IllegalArgumentException("用户工作空间目录配置不正确");
        }
        this.root = Path.of(root).toAbsolutePath().normalize().toString();
        this.hostRoot = hostRoot;
    }

    public String hostPath(Path path) {
        Path base = Path.of(root);
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            throw new IllegalArgumentException("用户文件不在配置的挂载目录内");
        }
        if (hostRoot.isBlank()) {
            return absolute.toString();
        }
        return hostRoot.replace('\\', '/').replaceAll("/+$", "") + "/" + base.relativize(absolute).toString()
            .replace('\\', '/');
    }
}
