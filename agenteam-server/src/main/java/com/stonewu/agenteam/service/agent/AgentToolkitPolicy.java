package com.stonewu.agenteam.service.agent;

import io.agentscope.harness.agent.HarnessAgent;

import java.util.Set;

/**
 * 构造后只保留平台明确开放的工具，框架新增默认工具不会自动对用户开放。
 */
public final class AgentToolkitPolicy {
    private AgentToolkitPolicy() {
    }

    public static void retain(HarnessAgent agent, Set<String> allowed) {
        for (String name : Set.copyOf(agent.getToolkit().getToolNames())) {
            if (!allowed.contains(name)) {
                agent.getToolkit().removeTool(name);
            }
        }
        if (!allowed.containsAll(agent.getToolkit().getToolNames())) {
            agent.close();
            throw new IllegalStateException("执行实例包含未允许的工具，不能启动");
        }
    }
}
