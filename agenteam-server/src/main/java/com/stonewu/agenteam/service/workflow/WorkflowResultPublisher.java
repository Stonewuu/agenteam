package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 最终正文来自结束节点；附件和引用只能复用本次真实输入或工具已经保存的内容。
 */
@Component
public class WorkflowResultPublisher {
    private record Result(String text, List<Map<String, Object>> files, List<Map<String, Object>> citations) {
    }

    private final WorkflowValues values;
    private final ObjectMapper json;
    private final ExecutionMessageMapper messages;

    public WorkflowResultPublisher(WorkflowValues values, ObjectMapper json, ExecutionMessageMapper messages) {
        this.values = values;
        this.json = json;
        this.messages = messages;
    }

    public void validate(WorkflowRunContext context, Identity identity, JsonNode output) {
        var result = prepare(context, output);
        context.frames().read(mapper -> {
            var state = mapper.presentation();
            var ids = new HashSet<String>();
            ids.add(state.id("workflow-result:" + identity.id()));
            result.files().forEach(file -> ids.add(state.id("workflow-file:" + identity.id() + ":" + file.get("id"))));
            result.citations().forEach(
                citation -> ids.add(state.id("workflow-citation:" + identity.id() + ":" + citation.get("chunkId"))));
            ids.removeAll(state.blocks().keySet());
            if (state.blocks().size() + ids.size() > 500) {
                throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_OUTPUT_LIMIT",
                    "本次输出已达到允许的长度，请缩小任务范围后重试。");
            }
            return null;
        });
    }

    public void publish(WorkflowRunContext context, Identity identity, WorkflowTraversal workflow) {
        if (!workflow.snapshot().status().equals("completed")) {
            return;
        }
        var output = values.read(workflow.node(workflow.graph().endId()).output());
        var result = prepare(context, output);
        context.update(mapper -> {
            var state = mapper.presentation();
            var changes = new ArrayList<ExecutionChange>();
            String resultId = state.id("workflow-result:" + identity.id()), stepId = state.id(
                "workflow-node:" + identity.id() + ":" + workflow.graph().endId());
            if (state.blocks().containsKey(resultId)) {
                return changes;
            }
            for (var file : result.files()) {
                add(state.blocks(), changes,
                    new ContentBlock(state.id("workflow-file:" + identity.id() + ":" + file.get("id")), "attachment",
                        null,
                        state.nextOrder(), "1", "", "completed", stepId, null, file, null,
                        String.valueOf(file.get("name")), null));
            }
            for (var citation : result.citations()) {
                add(state.blocks(), changes,
                    new ContentBlock(state.id("workflow-citation:" + identity.id() + ":" + citation.get("chunkId")),
                        "citation", null,
                        state.nextOrder(), "1", "", "completed", stepId, null, null, citation,
                        String.valueOf(citation.get("name")), null));
            }
            add(state.blocks(), changes,
                new ContentBlock(resultId, "text", null, state.nextOrder(), "1", result.text(), "completed", stepId,
                    null, null, null, null, null));
            return changes;
        });
    }

    private Result prepare(WorkflowRunContext context, JsonNode output) {
        String text = text(output);
        if (text.length() > 200000) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKFLOW_OUTPUT_TOO_LARGE",
                "流程最终正文过长，请减少结果或通过附件返回。");
        }
        var input = messages.find(context.run().enterpriseId(), context.run().conversationId(),
            context.run().inputMessageId()).orElseThrow();
        var known = context.frames().read(mapper -> new ArrayList<>(mapper.presentation().blocks().values()));
        known.addAll(input.blocks());
        List<Map<String, Object>> files = new ArrayList<>();
        for (var file : input.attachments()) {
            files.add(json.convertValue(file, new TypeReference<Map<String, Object>>() {
            }));
        }
        known.stream().map(ContentBlock::file).filter(file -> file != null).forEach(files::add);
        return new Result(text, references(output, "attachments", files), references(output, "citations",
            known.stream().map(ContentBlock::citation).filter(citation -> citation != null).toList()));
    }

    private List<Map<String, Object>> references(JsonNode output, String field, List<Map<String, Object>> known) {
        if (!output.has(field)) {
            return List.of();
        }
        if (!output.path(field).isArray()) {
            throw invalidReference();
        }
        var selected = new ArrayList<Map<String, Object>>();
        for (var value : output.path(field)) {
            // 使用同一读取器统一整数宽度，真实附件的字节数不能因 Java 数字类型不同而被拒绝。
            selected.add(known.stream().filter(item -> values.read(values.write(json.valueToTree(item))).equals(value))
                .findFirst().orElseThrow(WorkflowResultPublisher::invalidReference));
        }
        return List.copyOf(selected);
    }

    private String text(JsonNode output) {
        if (output.path("text").isTextual() && !output.path("text").asText().isBlank()) {
            return output.path("text").asText();
        }
        try {
            return "```json\n" + json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(output.has("result") ? output.get("result") : output) + "\n```";
        } catch (Exception invalid) {
            throw new IllegalStateException("流程最终结果无法显示", invalid);
        }
    }

    private void add(Map<String, ContentBlock> blocks, List<ExecutionChange> changes, ContentBlock block) {
        if (blocks.containsKey(block.id())) {
            return;
        }
        if (blocks.size() >= 500) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_OUTPUT_LIMIT",
                "本次输出已达到允许的长度，请缩小任务范围后重试。");
        }
        blocks.put(block.id(), block);
        changes.add(ExecutionChange.replace(block));
    }

    private static ApiException invalidReference() {
        return new ApiException(HttpStatus.CONFLICT, "WORKFLOW_REFERENCE_INVALID",
            "流程返回的附件或引用不属于本次已经取得的资料。");
    }
}
