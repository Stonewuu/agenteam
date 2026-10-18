package com.stonewu.agenteam.model.environment.response;

/** 仅返回用户可理解的运行状态，不暴露容器或执行节点信息。 */
public record EnvironmentView(boolean enabled, String status, boolean busy, int idleSeconds, Long lastActiveAt) {
}
