package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 容器按 Docker 主机中的实际文件目录区分部署，操作前再核对归属标签。
 */
public final class UserContainerOwnership {
    public static final String LABEL = "agenteam_user_storage";

    private UserContainerOwnership() {
    }

    public static String scope(String storagePath) {
        return Utf8Text.revision("user-container-storage", storagePath.replace('\\', '/'));
    }

    public static String name(String storagePath) {
        return "agenteam-user-" + scope(storagePath);
    }

    public static String ownedId(DockerWorkspaceCommands docker, String storagePath, Duration timeout,
                                 ToolCallControl control) {
        var found = docker.run(List.of("inspect", "--type=container",
            "--format={{.Id}} {{.Config.Labels." + LABEL + "}}", name(storagePath)), timeout, control);
        if (found.exitCode() != 0) {
            if (found.output().contains("No such")) {
                return null;
            }
            throw DockerWorkspaceCommands.unavailable(new IOException("无法读取用户容器归属：" + found.output()));
        }
        String[] values = found.output().trim().split("\\s+", 2);
        if (values.length != 2 || !values[0].matches("[0-9a-f]{64}") || !values[1].equals(scope(storagePath))) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_CONTAINER_OWNERSHIP_MISMATCH",
                "命令容器不属于当前部署，已停止操作，请检查工作目录配置。");
        }
        return values[0];
    }
}
