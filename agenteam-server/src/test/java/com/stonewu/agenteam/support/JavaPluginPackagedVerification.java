package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.plugin.javaworker.JavaPluginProcesses;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.provider.ToolProviderContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 从生产可执行包启动，验证主服务选择的独立进程入口能发现并执行示例插件。 */
public final class JavaPluginPackagedVerification {
    private JavaPluginPackagedVerification() {
    }

    public static void main(String[] arguments) throws Exception {
        var json = new ObjectMapper();
        byte[] artifact = Files.readAllBytes(Path.of(arguments[0]));
        try (var processes = new JavaPluginProcesses(); var control = new ToolCallControl(() -> {
        })) {
            var context = new ToolProviderContext("packaged-verification", Duration.ofSeconds(30), control);
            var discovered = processes.call("sample", artifact, json.createObjectNode().put("mode", "discover"),
                context, () -> {
                });
            if (!discovered.get(0).path("name").asText().equals("echo")) {
                throw new IllegalStateException("生产包未发现示例扩展");
            }
            var request = json.createObjectNode().put("mode", "invoke").put("tool", "echo");
            request.putObject("arguments").put("text", "已执行");
            request.putObject("configuration").put("prefix", "生产包验证：");
            request.putObject("credentialHeaders");
            var result = processes.call("sample", artifact, request, context, () -> {
            });
            if (!result.path("content").asText().equals("生产包验证：已执行")) {
                throw new IllegalStateException("生产包示例调用返回错误内容");
            }
            System.out.println("生产包已通过 PF4J 发现扩展，并在独立进程中完成示例调用。");
        }
    }
}
