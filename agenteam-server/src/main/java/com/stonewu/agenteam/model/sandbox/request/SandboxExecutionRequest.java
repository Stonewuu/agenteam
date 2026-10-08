package com.stonewu.agenteam.model.sandbox.request;

/**
 * 独立执行服务只接收逻辑工作目录和命令，不接收客户端主机路径。
 */
public record SandboxExecutionRequest(String callId, String command, String workingDirectory, long timeoutMillis,
                                      String networkPolicy) {
}
