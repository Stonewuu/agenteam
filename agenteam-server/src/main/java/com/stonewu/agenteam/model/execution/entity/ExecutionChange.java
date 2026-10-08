package com.stonewu.agenteam.model.execution.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.RunStepView;

/**
 * 一个消息块或步骤的待保存变化；仅在提交成功后才可作为已显示内容读取。
 */
public record ExecutionChange(ContentBlock block, RunStepView step, String baseRevision, String delta, JsonNode result,
                              JsonNode input) {
    public static ExecutionChange replace(ContentBlock block) {
        return new ExecutionChange(block, null, null, null, null, null);
    }

    public static ExecutionChange delta(ContentBlock block, String base, String text) {
        return new ExecutionChange(block, null, base, text, null, null);
    }

    public static ExecutionChange step(RunStepView step) {
        return new ExecutionChange(null, step, null, null, null, null);
    }

    public ExecutionChange withResult(JsonNode value) {
        return new ExecutionChange(block, step, baseRevision, delta, value, input);
    }

    public ExecutionChange withInput(JsonNode value) {
        return new ExecutionChange(block, step, baseRevision, delta, result, value);
    }
}
