package com.stonewu.agenteam.model.workflow.response;

import com.stonewu.agenteam.model.plugin.response.PluginToolView;
import com.stonewu.agenteam.model.resource.response.UsableVersionView;

import java.util.List;

/**
 * 节点编辑只读取固定版本的可选能力，不公开指令或认证配置。
 */
public record WorkflowDependencyOptions(String kind, String agentType, List<UsableVersionView> skills,
                                        List<PluginToolView> tools) {
}
