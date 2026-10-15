package com.stonewu.agenteam.mapper.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定工具名与严格参数结构；企业、用户、任务和工作身份不由模型传入。 */
@Component
public class CollaborationToolDefinitions {
    public static final Set<String> WRITES = Set.of("collab_publish_artifact", "collab_assign", "collab_handoff", "collab_wait",
        "collab_ask_user", "collab_submit_result", "collab_complete_task");
    public static final Set<String> READS = Set.of("collab_list_roles", "collab_read_context", "collab_read_artifact");
    private final ResourceJson json;

    public CollaborationToolDefinitions(ResourceJson json) {
        this.json = json;
    }

    public List<ToolDefinition> tools() {
        var result = new ArrayList<ToolDefinition>();
        result.add(tool("collab_list_roles", "分页读取本任务固定岗位及当前可用情况。只能使用返回的岗位编号，按 nextCursor 继续查询。", false,
            fields("cursor", text(30), "limit", integer(1, 10))));
        result.add(tool("collab_read_context", "分页读取本任务的要求、消息、成果或工作。返回内容可能分段，按 nextCursor 继续读取。", false,
            fields("section", choice("overview", "messages", "artifacts", "work"), "cursor", text(30), "limit", integer(1, 50)), "section"));
        result.add(tool("collab_read_artifact", "读取本任务固定版本成果。金额、日期等精确事实以原成果为准；hasMore 为真时继续读取。", false,
            fields("artifactId", text(100), "version", integer(1, Integer.MAX_VALUE), "offset", integer(0, Integer.MAX_VALUE), "limit", integer(1, 16000)),
            "artifactId", "version"));
        var publish = fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "artifactKey", text(100), "title", text(160),
            "summary", text(2000), "kind", choice("text", "json", "file"), "schemaName", text(80), "content", Map.of(), "fileId", text(100));
        result.add(tool("collab_publish_artifact", "保存本岗位成果的不可变版本。文件只能来自当前执行；内容最多 16 KiB，超出时先生成文件。", true, publish,
            "expectedInputRevision", "artifactKey", "title", "summary", "kind"));
        var assign = fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "targetRoleId", text(100), "goal", text(4000),
            "acceptanceCriteria", text(2000), "artifactRefs", references(), "dependsOnWorkIds", identifiers(), "required", Map.of("type", "boolean"), "replacesWorkItemId", text(100));
        result.add(tool("collab_assign", "向另一岗位委派独立协助工作，当前交付负责人不变。明确指定的岗位必须安排；替换旧工作只允许负责人。", true, assign,
            "expectedInputRevision", "targetRoleId", "goal", "acceptanceCriteria", "artifactRefs", "dependsOnWorkIds"));
        var handoff = fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "targetRoleId", text(100), "completedSummary", text(2000),
            "nextGoal", text(4000), "acceptanceCriteria", text(2000), "artifactRefs", references(), "dependsOnWorkIds", identifiers());
        result.add(tool("collab_handoff", "只有最终交付负责人可以移交。必须说明已完成内容并带成果；成功后结束本轮，不再修改业务。", true, handoff,
            "expectedInputRevision", "targetRoleId", "completedSummary", "nextGoal", "acceptanceCriteria", "artifactRefs", "dependsOnWorkIds"));
        result.add(tool("collab_wait", "等待本任务已有工作。成功后结束本轮，依赖齐备后系统会自动继续；不要轮询或继续修改业务。", true,
            fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "workItemIds", identifiers(), "reason", text(2000)),
            "expectedInputRevision", "workItemIds", "reason"));
        result.add(tool("collab_ask_user", "向原发起人提出问题并暂停，问题与选项会发送到任务来源群聊或私聊。只填写适合在该会话公开的具体问题，不附带私有文件正文、内部指令或推理；不能用它代替业务操作审批。", true,
            fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "question", text(2000), "options", array(text(200), 5)),
            "expectedInputRevision", "question"));
        var finish = fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "summary", text(2000), "artifactRefs", references());
        var prior = object(fields("workItemId", text(100), "artifactId", text(100), "version", integer(1, Integer.MAX_VALUE), "reason", text(2000)),
            "workItemId", "artifactId", "version", "reason");
        result.add(tool("collab_submit_result", "提交本项工作的成果并结束本轮。协助工作只完成自身；负责人有未完成依赖时自动转为等待。", true, finish,
            "expectedInputRevision", "summary", "artifactRefs"));
        finish.put("acceptedPriorResults", array(prior, 20));
        result.add(tool("collab_complete_task", "最终交付负责人在所有必需工作完成后结束任务。旧要求成果必须逐项说明仍符合新要求的理由。", true, finish,
            "expectedInputRevision", "summary", "artifactRefs", "acceptedPriorResults"));
        return List.copyOf(result);
    }

    private ToolDefinition tool(String name, String description, boolean write, Map<String, Object> properties, String... required) {
        JsonNode input = json.tree(object(properties, required));
        JsonNode output = json.tree(Map.of("type", "object"));
        String operation = write ? "write" : "read";
        String hash = json.hash(json.tree(Map.of("name", name, "inputSchema", input, "outputSchema", output, "operationClass", operation)));
        return new ToolDefinition(name, description, hash, input, output, json.tree(Map.of("readOnlyHint", !write)), operation,
            write, false, false, List.of(), 30);
    }

    private Map<String, Object> references() {
        return array(object(fields("artifactId", text(100), "version", integer(1, Integer.MAX_VALUE)), "artifactId", "version"), 20);
    }

    private Map<String, Object> identifiers() {
        return array(text(100), 20);
    }

    private Map<String, Object> array(Map<String, Object> item, int limit) {
        return Map.of("type", "array", "items", item, "maxItems", limit);
    }

    private Map<String, Object> object(Map<String, Object> fields, String... required) {
        return Map.of("type", "object", "properties", fields, "required", List.of(required), "additionalProperties", false);
    }

    private Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }

    private Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private Map<String, Object> choice(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private LinkedHashMap<String, Object> fields(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }
}
