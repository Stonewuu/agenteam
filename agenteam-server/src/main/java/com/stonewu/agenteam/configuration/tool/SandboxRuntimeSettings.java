package com.stonewu.agenteam.configuration.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 执行节点的限制和路径映射只接受部署配置，不接受模型参数。
 */
@Component
public record SandboxRuntimeSettings(int temporaryMb, int maximumConcurrent, String localRoot, String hostRoot) {

    public SandboxRuntimeSettings(@Value("${execution.sandbox.temporary-mb:256}") int temporaryMb,
                                  @Value("${execution.sandbox.maximum-concurrent:2}") int maximumConcurrent,
                                  @Value("${execution.sandbox.local-mount-root:}") String localRoot,
                                  @Value("${execution.sandbox.host-mount-root:}") String hostRoot) {
        if (temporaryMb < 16 || temporaryMb > 2048 || maximumConcurrent < 1 || maximumConcurrent > 64 || localRoot == null || hostRoot == null || localRoot.isBlank() != hostRoot.isBlank() || localRoot.contains(
            ",") || hostRoot.contains(",")) {
            throw new IllegalArgumentException("沙盒临时空间、并发数或主机目录映射不正确");
        }
        this.temporaryMb = temporaryMb;
        this.maximumConcurrent = maximumConcurrent;
        this.localRoot = localRoot;
        this.hostRoot = hostRoot;
    }

    public String hostPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (localRoot.isBlank()) {
            return absolute.toString();
        }
        Path base = Path.of(localRoot).toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            throw new IllegalArgumentException("沙盒输入目录不在配置的主机挂载范围内");
        }
        String relative = base.relativize(absolute).toString().replace('\\', '/');
        return hostRoot.replace('\\', '/').replaceAll("/+$", "") + (relative.isEmpty() ? "" : "/" + relative);
    }
}
