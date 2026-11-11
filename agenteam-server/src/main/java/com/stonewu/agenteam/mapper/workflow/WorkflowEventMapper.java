package com.stonewu.agenteam.mapper.workflow;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionPresentationState;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.model.execution.response.WorkflowStepDetails;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.model.workflow.entity.WorkflowInvocationState;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流与节点产生真实步骤和内容分组，与模型事件共用顺序、父节点和修改版本。
 */
@Component
public class WorkflowEventMapper {
    private final ObjectMapper json;

    public WorkflowEventMapper(ObjectMapper json) {
        this.json = json;
    }

    public record Identity(String id, String resourceId, String versionId, String name, String parentStepId,
                           String parentBlockId) {
    }

    public List<ExecutionChange> invocation(ExecutionPresentationState state, Identity identity,
                                            WorkflowInvocationState workflow, Instant now) {
        String id = state.id("workflow:" + identity.id());
        boolean errors = workflow.nodes().values().stream().anyMatch(node -> node.status().equals("failed"));
        String status = workflow.status();
        if (status.equals("running") && workflow.nodes().values().stream()
            .anyMatch(node -> Set.of("waiting_approval", "waiting_input").contains(node.status()))
            && workflow.nodes().values().stream().noneMatch(node -> node.status().equals("running"))) {
            status = workflow.nodes().values().stream().anyMatch(node -> "waiting_input".equals(node.status()))
                ? "waiting_input" : "waiting_approval";
        }
        String summary = workflow.errorMessage() != null ? workflow.errorMessage() : errors ? Messages.text("text.workflow.workflowEventMapper.invocation.1") : null;
        var details = new WorkflowStepDetails(identity.id(), identity.resourceId(), identity.versionId(), null, null);
        return group(state, id, identity.parentStepId(), identity.parentBlockId(), identity.name(), "workflow", status,
            summary, details, now, null, null);
    }

    public List<ExecutionChange> node(ExecutionPresentationState state, Identity identity, Node node,
                                      WorkflowInvocationState workflow, Instant now) {
        var progress = workflow.nodes().get(node.id());
        String id = state.id("workflow-node:" + identity.id() + ":" + node.id()), parent = state.id(
            "workflow:" + identity.id());
        var details = new WorkflowStepDetails(identity.id(), identity.resourceId(), identity.versionId(), node.id(),
            node.type());
        return group(state, id, parent, parent, node.name(), "workflow_node", progress.status(),
            progress.errorMessage(), details,
            now, progress.startedAt(), progress.finishedAt()).stream()
            .map(change -> change.step() == null ? change : change.withInput(read(progress.input()))
                .withResult(read(progress.output()))).toList();
    }

    private JsonNode read(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(value);
        } catch (Exception invalid) {
            throw new IllegalStateException(Messages.text("error.workflow.workflowEventMapper.read.1"), invalid);
        }
    }

    public List<ExecutionChange> finishAgent(ExecutionPresentationState state, String session, String status,
                                             Instant now) {
        String root = state.id("agent:" + session);
        var changes = new ArrayList<ExecutionChange>();
        var descendants = new HashSet<String>();
        descendants.add(root);
        for (var step : new ArrayList<>(state.steps().values())) {
            if (!descendants.contains(step.id()) && !descendants.contains(step.parentStepId())) {
                continue;
            }
            descendants.add(step.id());
            if (!Set.of("pending", "running", "waiting_approval", "waiting_input").contains(step.status())) {
                continue;
            }
            var next = new RunStepView(step.id(), step.parentStepId(), step.attemptId(), step.kind(), step.title(),
                step.displayOrder(), status,
                step.publicSummary(), step.startedAt(),
                Set.of("completed", "failed", "cancelled", "skipped").contains(status) ? now.toString() : null,
                step.workflow());
            state.steps().put(next.id(), next);
            changes.add(ExecutionChange.step(next));
        }
        return List.copyOf(changes);
    }

    private List<ExecutionChange> group(ExecutionPresentationState state, String id, String parentStep,
                                        String parentBlock,
                                        String label, String kind, String status, String summary,
                                        WorkflowStepDetails details, Instant now, Long started, Long finished) {
        var changes = new ArrayList<ExecutionChange>();
        var previous = state.steps().get(id);
        String start = started == null ? previous == null ? null : previous.startedAt() : Instant.ofEpochMilli(started)
            .toString();
        if (start == null && !Set.of("pending", "skipped", "cancelled").contains(status)) {
            start = now.toString();
        }
        String end = finished == null ? Set.of("completed", "failed", "cancelled", "skipped").contains(status)
            ? previous != null && previous.finishedAt() != null ? previous.finishedAt() : now.toString() : null
            : Instant.ofEpochMilli(finished).toString();
        var step = new RunStepView(id, parentStep, state.attemptId(), kind, label,
            previous == null ? state.nextOrder() : previous.displayOrder(),
            status, summary, start, end, details);
        if (!step.equals(previous)) {
            state.steps().put(id, step);
            changes.add(ExecutionChange.step(step));
        }
        var before = state.blocks().get(id);
        String text = summary == null ? "" : summary;
        if (before == null) {
            if (state.blocks().size() >= 500) {
                throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_OUTPUT_LIMIT",
                    Messages.text("error.agent.agentPublicEventMapper.outputLimit.1"));
            }
            var block = new ContentBlock(id, "workflow", parentBlock, state.nextOrder(), "1", text, status, id, null,
                null, null, label, null);
            state.blocks().put(id, block);
            changes.add(ExecutionChange.replace(block));
        } else if (!before.status().equals(status) || !before.text().equals(text)) {
            var block = before.update(text, status);
            state.blocks().put(id, block);
            changes.add(ExecutionChange.replace(block));
        }
        return List.copyOf(changes);
    }
}
