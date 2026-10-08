package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.RunStepView;

import java.util.*;

/**
 * 同次执行的消息与步骤共用一份顺序和修改版本；调用方必须在事件保存器的锁内修改。
 */
public final class ExecutionPresentationState {
    private final String runId;
    private final String attemptId;
    private final int attemptNo;
    private final Map<String, ContentBlock> blocks = new LinkedHashMap<>();
    private final Map<String, RunStepView> steps = new LinkedHashMap<>();
    private int order;

    public ExecutionPresentationState(String runId, String attemptId, int attemptNo, List<ContentBlock> initial) {
        this.runId = runId;
        this.attemptId = attemptId;
        this.attemptNo = attemptNo;
        for (var block : initial) {
            blocks.put(block.id(), block);
            order = Math.max(order, block.displayOrder());
        }
    }

    public void initialSteps(List<RunStepView> initial) {
        for (var step : initial) {
            if (step.attemptId().equals(attemptId)) {
                steps.put(step.id(), step);
            }
            order = Math.max(order, step.displayOrder());
        }
    }

    public int nextOrder() {
        return ++order;
    }

    public Map<String, ContentBlock> blocks() {
        return blocks;
    }

    public Map<String, RunStepView> steps() {
        return steps;
    }

    public List<ExecutionChange> finishTextBlocks(Set<String> ids, String status) {
        var result = new ArrayList<ExecutionChange>();
        for (String id : ids) {
            var block = blocks.get(id);
            if (block != null && (block.type().equals("text") || block.type().equals("thinking"))
                && List.of("pending", "running").contains(block.status())) {
                var completed = block.update(block.text(), status);
                blocks.put(id, completed);
                result.add(ExecutionChange.replace(completed));
            }
        }
        return result;
    }

    public String attemptId() {
        return attemptId;
    }

    public String id(String key) {
        return ExecutionStepIds.id(runId, attemptNo, key);
    }
}
