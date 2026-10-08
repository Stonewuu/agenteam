package com.stonewu.agenteam.model.memory.response;

import java.util.List;

/**
 * 新增偏好的可选主题和限制来自当前用户实际使用的员工配置。
 */
public record MemoryContextView(String agentId, String agentName, boolean enabled, boolean canSave,
                                List<String> allowedTopics, String unavailableReason, String agentIcon,
                                String agentColor) {
}
