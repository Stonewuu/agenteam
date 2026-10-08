package com.stonewu.agenteam.model.agent.entity;

import com.stonewu.agenteam.model.agent.response.AgentListingView;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;

/**
 * 内部使用资源身份和固定发布配置，公开介绍由专门的映射器选择字段。
 */
public record EmployeeSource(ResourceRecord resource, ResourceVersionRecord version, AgentListingView listing) {
}
