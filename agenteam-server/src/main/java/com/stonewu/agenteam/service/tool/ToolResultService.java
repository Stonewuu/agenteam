package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.file.GeneratedFileService;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 只向模型和页面交付移除秘密后的内容，大结果保存为原成员可读取的附件。
 */
@Service
public class ToolResultService {
    public record Prepared(JsonNode modelResult, JsonNode redacted, PreparedGeneratedFile file) {
    }

    private final ToolPayloadMapper payloads;
    private final GeneratedFileService files;
    private final ResourceJson json;
    private final ToolResultSettings settings;

    public ToolResultService(ToolPayloadMapper payloads, GeneratedFileService files, ResourceJson json,
                             ToolResultSettings settings) {
        this.payloads = payloads;
        this.files = files;
        this.json = json;
        this.settings = settings;
    }

    public ToolResultSettings settings() {
        return settings;
    }

    /**
     * 较大的已保存调用记录提供稳定引用，旧读取片段也可缩短，不额外生成附件。
     */
    public Prepared referenceForHistory(String callId, Prepared prepared, int maximum) {
        var value = prepared.modelResult();
        if (prepared.file() != null || !value.isObject() || value.has(ToolResultContent.REFERENCE)
            || Utf8Text.size(value.toString()) <= settings.previewBytes() * 2) {
            return prepared;
        }
        var reference = ToolResultContent.reference(callId, null, prepared.redacted(),
            Utf8Text.size(prepared.redacted().toPrettyString()), 0);
        ObjectNode withReference = value.deepCopy();
        withReference.set(ToolResultContent.REFERENCE, reference);
        return Utf8Text.size(withReference.toString()) <= maximum ? new Prepared(withReference, prepared.redacted(),
            null) : prepared;
    }

    public Prepared prepare(RunRecord run, ExecutionToolBinding binding, JsonNode result, List<String> secrets) {
        return prepare(run, binding, result, secrets, null, settings.inlineBytes(0));
    }

    public Prepared prepare(RunRecord run, ExecutionToolBinding binding, JsonNode result, List<String> secrets,
                            String callId, int maximumBytes) {
        JsonNode safe = payloads.redact(result, binding.definition().redactPaths(), secrets);
        int size = Utf8Text.size(safe.toString());
        if (WorkspaceToolDefinitions.handles(binding) && WorkspaceToolDefinitions.READ_TOOLS.contains(
            binding.definition().name())) {
            if (size > maximumBytes) {
                throw new IllegalStateException("文件工具的返回内容超过已分配的大小");
            }
            return new Prepared(safe, safe, null);
        }
        if (size <= settings.saveBytes() && size <= maximumBytes) {
            return new Prepared(safe, safe, null);
        }
        var file = files.toolResult(run, binding, safe, settings.maxFileBytes(), callId);
        var reference = ToolResultContent.reference(callId == null ? file.id() : callId, file.id(), safe, file.size(),
            settings.previewBytes());
        ObjectNode stored = reference.deepCopy();
        ToolResultContent.retainAuthorization(safe, stored, binding.resourceKind(), binding.definition().name());
        if (size <= maximumBytes) {
            ObjectNode withReference = safe.isObject() ? safe.deepCopy() : (ObjectNode) json.tree(
                Map.of("result", safe));
            withReference.set(ToolResultContent.REFERENCE, reference);
            return new Prepared(Utf8Text.size(withReference.toString()) <= maximumBytes ? withReference : safe, stored,
                file);
        }
        return new Prepared(ToolResultContent.fitReference(reference, maximumBytes), stored, file);
    }

    /**
     * 已保存的业务结果和恢复结果仍需遵守当前模型回合的合计字节限制。
     */
    public JsonNode forModel(ToolCallRecord call, JsonNode result, int maximumBytes) {
        if (Utf8Text.size(result.toString()) <= maximumBytes) {
            return result;
        }
        var stored = call.resultRedacted();
        if (stored != null && stored.path("path").asText().startsWith("tool-results/")) {
            var reference = (ObjectNode) stored.deepCopy();
            reference.remove(List.of("citations", "dataFields", "items"));
            return ToolResultContent.fitReference(reference, maximumBytes);
        }
        var reference = ToolResultContent.reference(call.id(),
            stored == null ? null : stored.path("fileId").asText(null), result,
            Utf8Text.size(result.toString()), settings.previewBytes());
        return ToolResultContent.fitReference(reference, maximumBytes);
    }
}
