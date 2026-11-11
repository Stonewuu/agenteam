package com.stonewu.agenteam.configuration.tool;

import com.stonewu.agenteam.service.i18n.Messages;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 执行节点的限制和路径映射只接受部署配置，不接受模型参数。
 */
@Component
public record SandboxRuntimeSettings(int temporaryMb, int maximumConcurrent, String localRoot, String hostRoot,
                                     String capacityRoot, String cgroupParent) {

    public SandboxRuntimeSettings(int temporaryMb, int maximumConcurrent, String localRoot, String hostRoot) {
        this(temporaryMb, maximumConcurrent, localRoot, hostRoot, "", "");
    }

    @Autowired
    public SandboxRuntimeSettings(@Value("${execution.sandbox.temporary-mb:256}") int temporaryMb,
                                  @Value("${execution.sandbox.maximum-concurrent:2}") int maximumConcurrent,
                                  @Value("${execution.sandbox.local-mount-root:}") String localRoot,
                                  @Value("${execution.sandbox.host-mount-root:}") String hostRoot,
                                  @Value("${execution.sandbox.capacity-root:}") String capacityRoot,
                                  @Value("${execution.sandbox.cgroup-parent:}") String cgroupParent) {
        if (temporaryMb < 16 || temporaryMb > 2048 || maximumConcurrent < 1 || maximumConcurrent > 64 || localRoot == null || hostRoot == null || localRoot.isBlank() != hostRoot.isBlank() || localRoot.contains(
            ",") || hostRoot.contains(",")) {
            throw new IllegalArgumentException(Messages.text("error.tool.sandboxRuntimeSettings.init.1"));
        }
        if (capacityRoot == null || cgroupParent == null || !capacityRoot.isBlank() && !Path.of(capacityRoot).isAbsolute()
            || !cgroupParent.isBlank() && !cgroupParent.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(Messages.text("error.tool.sandboxRuntimeSettings.init.2"));
        }
        this.temporaryMb = temporaryMb;
        this.maximumConcurrent = maximumConcurrent;
        this.localRoot = localRoot;
        this.hostRoot = hostRoot;
        this.capacityRoot = capacityRoot;
        this.cgroupParent = cgroupParent;
    }

    public String hostPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (localRoot.isBlank()) {
            return absolute.toString();
        }
        Path base = Path.of(localRoot).toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            throw new IllegalArgumentException(Messages.text("error.tool.sandboxRuntimeSettings.hostPath.1"));
        }
        String relative = base.relativize(absolute).toString().replace('\\', '/');
        return hostRoot.replace('\\', '/').replaceAll("/+$", "") + (relative.isEmpty() ? "" : "/" + relative);
    }
}
