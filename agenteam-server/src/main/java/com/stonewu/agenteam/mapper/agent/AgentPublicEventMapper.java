package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionPresentationState;
import com.stonewu.agenteam.mapper.tool.ToolDisplayNameMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadPreview;
import com.stonewu.agenteam.model.agent.entity.AgentEventRecord;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ContentBlock.ToolBlockDetails;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.agent.ExecutionGuardMiddleware;
import com.stonewu.agenteam.service.agent.SubagentToolEvents;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 沿用来源、回复、块和工具编号的关联，公开事件只保留明确允许展示的内容。
 */
public final class AgentPublicEventMapper {

    private final RunRecord run;

    private final String attempt;

    private final ObjectMapper json;

    private final ExecutionPresentationState presentation;

    private final Map<String, ContentBlock> blocks;

    private final Map<String, RunStepView> steps;

    public record Scope(String sessionId, String parentBlockId, String parentStepId, String label) {
    }

    private final Map<String, Scope> scopes = new LinkedHashMap<>();

    private final Set<String> directToolSessions = new HashSet<>();

    private final Set<String> workflowTools = new HashSet<>();

    private final Set<String> workflowCalls = new HashSet<>();

    private record Source(String session, String parentCall, String owner) {

        private String key() {
            return parentCall == null ? session : session + ":" + parentCall;
        }
    }

    private final Map<String, Source> lifecycleSessions = new LinkedHashMap<>();

    private final Map<String, String> toolInputs = new LinkedHashMap<>();

    private final Map<String, String> toolOutputs = new LinkedHashMap<>();

    private final Map<String, String> childOutcomes = new LinkedHashMap<>();

    private final Map<String, ExecutionToolBinding> platformTools = new LinkedHashMap<>();

    private final Map<String, ExecutionToolBinding> blockTools = new LinkedHashMap<>();

    private BiFunction<String, String, ToolCallRecord> toolRecords = (session, call) -> null;

    private Function<String, Map<String, Object>> artifacts = id -> null;

    public AgentPublicEventMapper(RunRecord run, String attempt, ObjectMapper json, List<ContentBlock> initialBlocks) {
        this.run = run;
        this.attempt = attempt;
        this.json = json;
        presentation = new ExecutionPresentationState(run.id(), attempt, run.currentAttemptNo(), initialBlocks);
        blocks = presentation.blocks();
        steps = presentation.steps();
        scopes.put(run.conversationId(), new Scope(run.conversationId(), null, null, "处理任务"));
    }

    public void platformTools(Map<String, ExecutionToolBinding> tools,
                              BiFunction<String, String, ToolCallRecord> records) {
        platformTools.putAll(tools);
        var previous = toolRecords;
        toolRecords = (session, call) -> {
            var found = records.apply(session, call);
            return found == null ? previous.apply(session, call) : found;
        };
    }

    public void initialSteps(List<RunStepView> initial) {
        presentation.initialSteps(initial);
    }

    public ExecutionPresentationState presentation() {
        return presentation;
    }

    public void scope(Scope scope) {
        var previous = scopes.putIfAbsent(scope.sessionId(), scope);
        if (previous != null && !previous.equals(scope)) {
            throw new IllegalStateException("同一智能体会话不能属于不同流程节点");
        }
    }

    public void directToolScope(Scope scope) {
        scope(scope);
        directToolSessions.add(scope.sessionId());
    }

    public void workflowTools(Set<String> names) {
        workflowTools.addAll(names);
    }

    public void artifacts(Function<String, Map<String, Object>> lookup) {
        artifacts = lookup;
    }

    public List<ExecutionChange> accept(AgentEventRecord event) {
        var changes = new ArrayList<ExecutionChange>();
        String type = event.eventType();
        if (type.equals("AGENT_RESULT") || type.equals("CUSTOM")) {
            return changes;
        }
        Source source = source(event);
        String session = source.key();
        Scope scope = scopes.get(source.owner());
        String parent = source.parentCall() == null ? scope.parentBlockId() : id("child:" + session);
        if (type.equals("AGENT_START")) {
            String reply = required(event.replyId());
            lifecycleSessions.put(reply, source);
            String label = source.parentCall() == null ? scope.label() : event.metadata()
                .get(SubagentToolEvents.LABEL) instanceof String name ? publicLabel(name) : "子智能体";
            step(changes, id("agent:" + session), source.parentCall() == null ? scope.parentStepId() : id(
                "tool-step:" + source.owner() + ":" + source.parentCall()), "agent", label, "running", event, null);
            if (source.parentCall() != null) {
                var existing = blocks.get(parent);
                var child = existing == null ? newBlock(parent, "subagent",
                    id("tool:" + source.owner() + ":" + source.parentCall()), label, id("agent:" + session), "running",
                    null) : existing.update(existing.text(), "running");
                if (event.metadata().get(SubagentToolEvents.ICON_METADATA) instanceof String icon) {
                    child = child.withAgentAppearance(icon,
                        event.metadata().get(SubagentToolEvents.COLOR_METADATA) instanceof String color ? color : null);
                }
                replace(changes, child);
            }
        } else if (type.equals("AGENT_END")) {
            if (source.parentCall() != null && event.metadata()
                .get(SubagentToolEvents.CHILD_STATUS) instanceof String status) {
                childOutcomes.put(source.owner() + ":" + source.parentCall(), status);
                finishChild(changes, session, parent, status, event);
            }
        } else if (type.equals("MODEL_CALL_START") || type.equals("MODEL_CALL_END")) {
            String key = id("model:" + session + ":" + required(event.replyId()));
            step(changes, key, id("agent:" + session), "model", "生成回复",
                type.endsWith("START") ? "running" : "completed", event,
                type.endsWith("END") ? event.details().get("usage") : null);
        } else if (type.startsWith("TEXT_BLOCK_") || type.startsWith("THINKING_BLOCK_")) {
            text(event, session, parent, type.startsWith("THINKING_") ? "thinking" : "text", changes);
        } else if (type.startsWith("TOOL_CALL_") || type.startsWith("TOOL_RESULT_")) {
            tool(event, session, parent, changes);
        } else if (type.equals("REQUIRE_USER_CONFIRM") || type.equals("REQUIRE_EXTERNAL_EXECUTION")) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
                "本次任务需要的工具尚不能继续执行。");
        }
        return List.copyOf(changes);
    }

    private void text(AgentEventRecord event, String session, String parent, String kind,
                      List<ExecutionChange> changes) {
        String key = id(kind + ":" + session + ":" + required(event.replyId()) + ":" + required(event.blockId()));
        ContentBlock block = blocks.get(key);
        if (block == null) {
            block = newBlock(key, kind, parent, kind.equals("thinking") ? "思考" : null,
                id("model:" + session + ":" + event.replyId()), "running", null);
            replace(changes, block);
        }
        if (event.eventType().endsWith("_DELTA")) {
            for (String delta : split(event.mergedDelta())) {
                if (block.text().length() + delta.length() > 200_000) {
                    throw outputLimit();
                }
                var updated = block.update(block.text() + delta, "running");
                changes.add(ExecutionChange.delta(updated, block.revision(), delta));
                blocks.put(key, updated);
                block = updated;
            }
        } else if (event.eventType().endsWith("_END")) {
            String text = kind.equals("text") && event.details()
                .get("text") instanceof String full ? full : block.text();
            if (text.length() > 200_000) {
                throw outputLimit();
            }
            replace(changes, block.update(text, "completed"));
        }
    }

    private void tool(AgentEventRecord event, String session, String parent, List<ExecutionChange> changes) {
        String call = required(event.toolCallId());
        String key = id("tool:" + session + ":" + call);
        if (event.toolCallName() != null && !event.toolCallName().isBlank() && workflowTools.stream()
            .anyMatch(name -> name.startsWith(event.toolCallName()))) {
            workflowCalls.add(key);
        }
        if (workflowCalls.contains(key)) {
            return;
        }
        if (event.toolCallName() != null && platformTools.containsKey(event.toolCallName())) {
            blockTools.put(key, platformTools.get(event.toolCallName()));
        }
        if (!blockTools.containsKey(key)) {
            var saved = toolRecords.apply(session, call);
            if (saved != null) {
                platformTools.values().stream().filter(binding -> binding.matches(saved)).findFirst()
                    .ifPresent(binding -> blockTools.put(key, binding));
            }
        }
        if (blockTools.containsKey(key)) {
            platformTool(event, session, parent, key, blockTools.get(key), changes);
            return;
        }
        ContentBlock block = blocks.get(key);
        if (block == null) {
            String name = event.toolCallName() == null ? "tool" : event.toolCallName();
            block = newBlock(key, "tool", parent, "子智能体", id("tool-step:" + session + ":" + call), "pending",
                new ToolBlockDetails(call, name, null, "", "", "running", "pending"));
            replace(changes, block);
        }
        var tool = block.tool();
        String input = toolInputs.getOrDefault(key, ""), result = toolOutputs.getOrDefault(key,
            ""), callStatus = tool.callStatus(), resultStatus = tool.resultStatus(), status = block.status();
        switch (event.eventType()) {
            case "TOOL_CALL_DELTA" -> input += event.mergedDelta() == null ? "" : event.mergedDelta();
            case "TOOL_CALL_END" -> callStatus = "completed";
            case "TOOL_RESULT_START" -> {
                status = "running";
                resultStatus = "running";
            }
            case "TOOL_RESULT_TEXT_DELTA" -> result += event.mergedDelta() == null ? "" : event.mergedDelta();
            case "TOOL_RESULT_DATA_DELTA" -> {
                /* 资料必须由对应模块验证后建立公开引用。 */
            }
            case "TOOL_RESULT_END" -> {
                status = "INTERRUPTED".equals(event.state()) ? "cancelled" : Set.of("ERROR", "DENIED")
                    .contains(event.state() == null ? "" : event.state()) ? "failed" : "completed";
                String childStatus = childOutcomes.get(session + ":" + call);
                if (childStatus != null && !childStatus.equals("completed")) {
                    status = childStatus;
                }
                resultStatus = status;
            }
            default -> {
            }
        }
        if (input.length() > 200_000 || result.length() > 200_000) {
            throw outputLimit();
        }
        toolInputs.put(key, input);
        toolOutputs.put(key, result);
        String name = event.toolCallName() == null || event.toolCallName()
            .equals("__fragment__") ? tool.name() : event.toolCallName();
        var updated = new ContentBlock(block.id(), block.type(), block.parentBlockId(), block.displayOrder(),
            next(block.revision()), "", status, block.stepId(), null, null, null, block.label(),
            new ToolBlockDetails(call, name, null, publicInput(input),
                status.equals("failed") ? "子任务未完成，请查看已保存的部分结果。" : publicResult(result), callStatus,
                resultStatus));
        replace(changes, updated);
        step(changes, block.stepId(), id("agent:" + session), "tool", "子智能体", status, event, null);
    }

    private void platformTool(AgentEventRecord event, String session, String parent, String key,
                              ExecutionToolBinding binding, List<ExecutionChange> changes) {
        var block = blocks.get(key);
        String name = binding.definition().name();
        String sourceKind = Set.of("knowledge", "data")
            .contains(binding.resourceKind()) ? binding.resourceKind() : null;
        if (block == null) {
            block = newBlock(key, "tool", parent, publicLabel(ToolDisplayNameMapper.label(binding)),
                id("tool-step:" + session + ":" + event.toolCallId()), "pending",
                new ToolBlockDetails(event.toolCallId(), name, sourceKind, "", "", "running", "pending"));
            replace(changes, block);
        }
        String status = block.status(), callStatus = block.tool().callStatus(), resultStatus = block.tool()
            .resultStatus();
        if (event.eventType().equals("TOOL_CALL_END")) {
            callStatus = "completed";
        }
        if (event.eventType().equals("TOOL_RESULT_START")) {
            status = "running";
            resultStatus = "running";
        }
        if (event.eventType().equals("TOOL_RESULT_END")) {
            status = "INTERRUPTED".equals(event.state()) ? "cancelled" : Set.of("ERROR", "DENIED")
                .contains(event.state() == null ? "" : event.state()) ? "failed" : "completed";
            resultStatus = status;
        }
        var record = toolRecords.apply(session, event.toolCallId());
        if (record != null && event.eventType().equals("TOOL_RESULT_END")) {
            status = switch (record.status()) {
                case "succeeded" -> "completed";
                case "cancelled" -> "TOOL_USER_REJECTED".equals(record.errorCode()) ? "skipped" : "cancelled";
                case "failed", "unknown" -> "failed";
                default -> status;
            };
            resultStatus = status;
        }
        String input = record == null ? "" : ToolPayloadPreview.value(record.requestRedacted());
        String output = record == null || record.resultRedacted() == null ? "" : sourceKind == null && record.resultRedacted()
            .path("truncated").asBoolean(false) ? record.resultRedacted().path("content")
            .asText() : ToolPayloadPreview.value(record.resultRedacted());
        if ((record != null && "TOOL_USER_REJECTED".equals(record.errorCode())) || "DENIED".equals(event.state())) {
            output = "用户已拒绝本次操作，未发送请求。";
        }
        replace(changes, new ContentBlock(block.id(), block.type(), block.parentBlockId(), block.displayOrder(),
            next(block.revision()), "", status, block.stepId(), block.approvalId(), null, null, block.label(),
            new ToolBlockDetails(event.toolCallId(), name, sourceKind, input, output, callStatus, resultStatus)));
        step(changes, block.stepId(),
            directToolSessions.contains(session) ? scopes.get(session).parentStepId() : id("agent:" + session), "tool",
            block.label(), status, event, null);
        if (binding.resourceKind().equals("knowledge") && event.eventType()
            .equals("TOOL_RESULT_END") && record != null && record.status()
            .equals("succeeded") && record.resultRedacted() != null) {
            int ordinal = 0;
            for (var citation : record.resultRedacted().path("citations")) {
                String citationId = id("tool-citation:" + session + ":" + event.toolCallId() + ":" + ordinal++);
                if (!blocks.containsKey(citationId)) {
                    replace(changes,
                        new ContentBlock(citationId, "citation", parent, presentation.nextOrder(), "1", "", "completed",
                            block.stepId(), null, null,
                            json.convertValue(citation, new TypeReference<Map<String, Object>>() {
                            }), citation.path("name").asText(), null));
                }
            }
        }
        boolean exported = record != null && record.toolName().equals("export_file") && List.of("agent", "workflow")
            .contains(record.resourceKind());
        if (event.eventType()
            .equals("TOOL_RESULT_END") && record != null && record.resultRedacted() != null && (record.resultRedacted()
            .path("truncated").asBoolean(false) || exported)) {
            String artifactId = id("tool-file:" + session + ":" + event.toolCallId());
            var file = artifacts.apply(record.resultRedacted().path("fileId").asText());
            if (file != null && !blocks.containsKey(artifactId)) {
                replace(changes,
                    new ContentBlock(artifactId, "attachment", parent, presentation.nextOrder(), "1", "", "completed",
                        block.stepId(), null, file, null, exported ? "生成文件" : "完整工具结果", null));
            }
        }
    }

    private void finishChild(List<ExecutionChange> changes, String session, String parent, String status,
                             AgentEventRecord event) {
        var child = blocks.get(parent);
        if (child == null || !List.of("pending", "running", "waiting_approval").contains(child.status())) {
            return;
        }
        replace(changes, child.update(child.text(), status));
        String agentStep = id("agent:" + session);
        for (var entry : new ArrayList<>(steps.values())) {
            if ((entry.id().equals(agentStep) || agentStep.equals(entry.parentStepId())) && List.of("pending",
                "running", "waiting_approval").contains(entry.status())) {
                step(changes, entry.id(), entry.parentStepId(), entry.kind(), entry.title(), status, event, null);
            }
        }
        for (var block : new ArrayList<>(blocks.values())) {
            if (parent.equals(block.parentBlockId()) && List.of("pending", "running", "waiting_approval")
                .contains(block.status())) {
                replace(changes, block.update(block.text(), status));
            }
        }
    }

    private ContentBlock newBlock(String key, String type, String parent, String label, String step, String status,
                                  ToolBlockDetails tool) {
        if (blocks.size() >= 500) {
            throw outputLimit();
        }
        return new ContentBlock(key, type, parent, presentation.nextOrder(), "1", "", status, step, null, null, null,
            label, tool);
    }

    private void replace(List<ExecutionChange> changes, ContentBlock block) {
        blocks.put(block.id(), block);
        changes.add(ExecutionChange.replace(block));
    }

    private void step(List<ExecutionChange> changes, String id, String parent, String kind, String title, String status,
                      AgentEventRecord event, Object usage) {
        var previous = steps.get(id);
        if (previous != null && previous.status().equals(status) && usage == null) {
            return;
        }
        int position = previous == null ? presentation.nextOrder() : previous.displayOrder();
        String started = previous == null || previous.startedAt() == null ? status.equals(
            "pending") ? null : event.startedAt() : previous.startedAt();
        String finished = Set.of("completed", "failed", "cancelled", "skipped")
            .contains(status) ? event.finishedAt() : null;
        var step = new RunStepView(id, parent, attempt, kind, title, position, status, null, started, finished, null);
        steps.put(id, step);
        var change = ExecutionChange.step(step);
        changes.add(usage == null ? change : change.withResult(json.valueToTree(Map.of("usage", usage))));
    }

    private Source source(AgentEventRecord event) {
        String parentCall = event.metadata().get(SubagentToolEvents.PARENT_CALL) instanceof String value ? value : null;
        String owner = event.metadata().get(
            ExecutionGuardMiddleware.OWNER_SESSION_METADATA) instanceof String value ? value : run.conversationId();
        if (!scopes.containsKey(owner)) {
            throw new IllegalStateException("智能体事件没有登记所属流程节点");
        }
        if (event.eventType().equals("AGENT_START")) {
            Object value = event.details().get("sessionId");
            if (value instanceof String session && !session.isBlank()) {
                return identified(session, parentCall, owner);
            }
        }
        if (event.eventType().equals("AGENT_END") && lifecycleSessions.containsKey(event.replyId())) {
            return lifecycleSessions.get(event.replyId());
        }
        Object value = event.metadata().get(ExecutionGuardMiddleware.SESSION_METADATA);
        if (value instanceof String session && !session.isBlank()) {
            return identified(session, parentCall, owner);
        }
        if (event.source() == null || event.source().isBlank()) {
            return new Source(owner, null, owner);
        }
        throw new IllegalStateException("子任务事件没有明确的会话身份，不能猜测归属");
    }

    private Source identified(String session, String parentCall, String owner) {
        if (session.equals(owner)) {
            return new Source(session, null, owner);
        }
        if (parentCall == null || parentCall.isBlank()) {
            throw new IllegalStateException("子任务事件没有发起工具的编号，不能猜测归属");
        }
        return new Source(session, parentCall, owner);
    }

    private String id(String key) {
        return presentation.id(key);
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("框架事件缺少关联编号");
        }
        return value;
    }

    private String next(String revision) {
        return Long.toString(Math.addExact(Long.parseLong(revision), 1));
    }

    private ApiException outputLimit() {
        return new ApiException(HttpStatus.CONFLICT, "EXECUTION_OUTPUT_LIMIT",
            "本次输出已达到允许的长度，请缩小任务范围后重试。");
    }

    private String publicInput(String value) {
        try {
            var input = json.readTree(value);
            if (input == null || !input.isObject()) {
                return "";
            }
            return input.has("task") ? input.path("task").asText() : input.path("message").asText();
        } catch (Exception incomplete) {
            return "";
        }
    }

    private String publicResult(String value) {
        if (value.startsWith("\"")) {
            try {
                var decoded = json.readTree(value);
                if (!decoded.isTextual()) {
                    return "";
                }
                value = decoded.textValue();
            } catch (Exception incomplete) {
                return "";
            }
        }
        String result = value.replaceFirst(
            "(?s)^(?:(?:agent_key|agent_id|session_id|task_id|status):[^\\r\\n]*(?:\\r?\\n|$))+", "").stripLeading();
        if (List.of("agent_key:", "agent_id:", "session_id:", "task_id:", "status:").stream()
            .anyMatch(header -> header.startsWith(result))) {
            return "";
        }
        return result.replaceFirst("^reply:\\s*", "");
    }

    private String publicLabel(String value) {
        String text = value.strip();
        return text.substring(0, text.offsetByCodePoints(0, Math.min(200, text.codePointCount(0, text.length()))));
    }

    private List<String> split(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        var parts = new ArrayList<String>();
        int start = 0, size = 0;
        for (int i = 0; i < value.length(); ) {
            int point = value.codePointAt(i), width = Character.charCount(point);
            int bytes = point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
            if (size + bytes > 4096) {
                parts.add(value.substring(start, i));
                start = i;
                size = 0;
            }
            size += bytes;
            i += width;
        }
        parts.add(value.substring(start));
        return parts;
    }
}
