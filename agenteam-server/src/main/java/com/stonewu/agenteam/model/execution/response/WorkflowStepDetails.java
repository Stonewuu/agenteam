package com.stonewu.agenteam.model.execution.response;

/**
 * 步骤对应的真实流程与节点，用于测试页面定位；整个流程分组的节点字段为空。
 */
public record WorkflowStepDetails(String invocationId, String resourceId, String versionId, String nodeId,
                                  String nodeType) {
}
