package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 脱敏后的完整结果保存在调用记录中，模型直接接收正文，不自动生成结果附件。
 */
@Service
public class ToolResultService {
    public record Prepared(JsonNode modelResult, JsonNode redacted, PreparedGeneratedFile file) {
    }

    private final ToolPayloadMapper payloads;
    private final ToolResultSettings settings;

    public ToolResultService(ToolPayloadMapper payloads, ToolResultSettings settings) {
        this.payloads = payloads;
        this.settings = settings;
    }

    public ToolResultSettings settings() {
        return settings;
    }

    public Prepared prepare(RunRecord run, ExecutionToolBinding binding, JsonNode result, List<String> secrets) {
        return prepare(run, binding, result, secrets, null, settings.inlineBytes(0));
    }

    public Prepared prepare(RunRecord run, ExecutionToolBinding binding, JsonNode result, List<String> secrets,
                            String callId, int maximumBytes) {
        JsonNode safe = payloads.redact(result, binding.definition().redactPaths(), secrets);
        int size = Utf8Text.size(safe.toString());
        if (size > settings.maxFileBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOOL_RESULT_TOO_LARGE",
                "工具返回内容超过保存上限，请缩小查询范围或使用分页后继续。");
        }
        if (WorkspaceToolDefinitions.handles(binding) && WorkspaceToolDefinitions.READ_TOOLS.contains(
            binding.definition().name())) {
            if (size > maximumBytes) {
                throw new IllegalStateException("文件工具的返回内容超过已分配的大小");
            }
            return new Prepared(safe, safe, null);
        }
        if (size <= maximumBytes) {
            return new Prepared(safe, safe, null);
        }
        if (callId == null) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOOL_RESULT_TOO_LARGE",
                "工具返回内容过多，请缩小查询范围后继续。");
        }
        // 路径按需读取已有调用记录，不创建文件；尽量利用本轮空间直接提供原文。
        var reference = ToolResultContent.reference(callId, null, safe, size, maximumBytes);
        return new Prepared(ToolResultContent.fitReference(reference, maximumBytes), safe, null);
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
            Utf8Text.size(result.toString()), maximumBytes);
        return ToolResultContent.fitReference(reference, maximumBytes);
    }
}
