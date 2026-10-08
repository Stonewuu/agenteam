package com.stonewu.agenteam.model.memory.response;

/**
 * 仅列出本人确实保存过偏好的员工，便于停用员工后仍能删除自己的内容。
 */
public record MemoryAgentView(String agentId, String agentName, long memoryCount, String agentIcon, String agentColor) {
}
