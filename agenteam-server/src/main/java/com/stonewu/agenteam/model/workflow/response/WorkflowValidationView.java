package com.stonewu.agenteam.model.workflow.response;

import java.util.List;

/**
 * 校验只返回实际发现的问题，并提供对应节点、连线或字段位置。
 */
public record WorkflowValidationView(boolean valid, List<Problem> errors) {
    public record Problem(String nodeId, String edgeId, String field, String code, String message) {
    }
}
