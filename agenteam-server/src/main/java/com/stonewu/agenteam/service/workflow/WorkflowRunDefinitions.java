package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 只从本次提交时保存的固定依赖读取节点资源，不临时选择最新版本。
 */
public final class WorkflowRunDefinitions {
    private WorkflowRunDefinitions() {
    }

    public static JsonNode dependency(RunRecord run, String kind, String version) {
        for (var dependency : run.executionConfig().path("dependencies")) {
            if (dependency.path("kind").asText().equals(kind) && dependency.path("versionId").asText()
                .equals(version)) {
                return dependency;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE", "当前流程节点的固定资源不可用。");
    }
}
