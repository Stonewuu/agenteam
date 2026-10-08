package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 原生子任务和工作流返回值也有完整记录与字节限制，不重复执行已经完成的委派。
 */
@Service
public class DelegatedToolResultService {
    public static final String REFERENCE = "agenteamDelegatedResultReference";
    public static final String HEADER = "agenteamDelegatedResultHeader";
    private final WorkspaceToolDefinitions definitions;
    private final ToolCallTransactions transactions;
    private final ToolResultService results;
    private final ToolCallMapper calls;
    private final ToolPayloadMapper payloads;
    private final ResourceJson json;

    public DelegatedToolResultService(WorkspaceToolDefinitions definitions, ToolCallTransactions transactions,
                                      ToolResultService results,
                                      ToolCallMapper calls, ToolPayloadMapper payloads, ResourceJson json) {
        this.definitions = definitions;
        this.transactions = transactions;
        this.results = results;
        this.calls = calls;
        this.payloads = payloads;
        this.json = json;
    }

    public ToolResultBlock save(RunRecord run, JobLease lease, String session, ToolUseBlock use, ToolResultBlock result,
                                int maximum) {
        var binding = definitions.delegatedResults(run).get(use.getName());
        if (binding == null) {
            return result;
        }
        String content = text(result);
        // 框架的注解工具把字符串序列化成 JSON 字符串，先还原换行再识别继续调用编号。
        if (Set.of("agent_spawn", "agent_send").contains(use.getName()) && result.getOutput()
            .size() == 1 && content.startsWith("\"")) {
            var decoded = json.read(content);
            if (decoded.isTextual()) {
                content = decoded.asText();
            }
        }
        var recordUse = ToolUseBlock.builder().id(use.getId()).name(binding.alias())
            .input(Map.of("sourceCallId", use.getId(),
                "sourceArgumentsHash", json.hash(json.tree(use.getInput())))).build();
        transactions.prepare(lease, binding, session, null, recordUse);
        var call = transactions.start(lease, binding, session, recordUse);
        var metadata = new LinkedHashMap<>(result.getMetadata());
        ObjectNode reference;
        String displayed;
        String header = header(content);
        if (call.resultEncrypted() != null) {
            var saved = transactions.result(call);
            displayed = saved.path("content").asText();
            reference = (ObjectNode) saved.path(ToolResultContent.REFERENCE);
            if (Utf8Text.size(displayed) > maximum) {
                displayed = format(reference, header, maximum);
            }
        } else {
            var safe = payloads.redact(
                json.tree(Map.of("content", content, "isError", result.getState() == ToolResultState.ERROR)), List.of(),
                List.of());
            var prepared = results.prepare(run, binding, safe, List.of(), call.id(),
                Math.max(256, maximum - Utf8Text.size(header) - 16));
            var origins = new LinkedHashSet<String>();
            var inputs = new LinkedHashSet<String>();
            for (var source : calls.sourceResultsForContext(run)) {
                if (Set.of("agent", "workflow").contains(source.resourceKind()) && source.resultRedacted() != null) {
                    source.resultRedacted().path("sourceResultIds").forEach(id -> origins.add(id.asText()));
                    source.resultRedacted().path("inputFileIds").forEach(id -> inputs.add(id.asText()));
                } else {
                    origins.add(source.id());
                }
            }
            ObjectNode stored = prepared.redacted().deepCopy();
            stored.set("sourceResultIds", json.tree(origins));
            stored.set("inputFileIds", json.tree(inputs));
            reference = ToolResultContent.reference(call.id(), prepared.file() == null ? null : prepared.file().id(),
                safe, Utf8Text.size(safe.toPrettyString()), 0);
            String sanitized = ToolResultContent.text(safe);
            displayed = Utf8Text.size(sanitized) <= maximum ? sanitized : format(reference, header, maximum);
            var withReference = displayed + "\n\n完整结果：" + reference;
            if (Utf8Text.size(sanitized) <= maximum && Utf8Text.size(withReference) <= maximum) {
                displayed = withReference;
            }
            var saved = json.tree(Map.of("content", displayed, ToolResultContent.REFERENCE, reference));
            transactions.complete(lease, call.id(), new ToolResultService.Prepared(saved, stored, prepared.file()),
                result.getState() == ToolResultState.ERROR ? "DELEGATED_RESULT_FAILED" : null,
                result.getState() == ToolResultState.ERROR ? "委派任务返回失败结果。" : null);
        }
        metadata.put(REFERENCE, json.object(reference));
        metadata.put(HEADER, header);
        return new ToolResultBlock(result.getId(), result.getName(),
            List.of(TextBlock.builder().text(displayed).build()), metadata, result.getState());
    }

    public static String text(ToolResultBlock result) {
        return result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
            .map(TextBlock::getText).collect(Collectors.joining("\n\n"));
    }

    public static String format(ObjectNode reference, String header, int maximum) {
        String prefix = Utf8Text.prefix(header, Math.max(0, maximum - 256));
        return prefix + (prefix.isEmpty() ? "" : "\n\n") + ToolResultContent.fitReference(reference,
            maximum - Utf8Text.size(prefix) - 2);
    }

    private static String header(String content) {
        StringBuilder result = new StringBuilder();
        for (String line : Utf8Text.prefix(content, 2048).split("\n", 8)) {
            if (!line.matches("(agent_key|agent_id|session_id|subagent_id|task_id): .+")) {
                break;
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }
}
