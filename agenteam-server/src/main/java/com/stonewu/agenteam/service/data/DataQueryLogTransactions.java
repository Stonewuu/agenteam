package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.request.DataQueryRequest;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理查询记录实际草稿版本，参数加密；日志中保留条件字段及运算而隐藏比较值。
 */
@Service
public class DataQueryLogTransactions {
    private final DataResourcePolicy policy;
    private final ToolCallMapper calls;
    private final ResourceJson json;
    private final ToolPayloadMapper payloads;
    private final PayloadEncryption encryption;
    private final Clock clock;

    public DataQueryLogTransactions(DataResourcePolicy policy, ToolCallMapper calls, ResourceJson json,
                                    ToolPayloadMapper payloads, PayloadEncryption encryption, Clock clock) {
        this.policy = policy;
        this.calls = calls;
        this.json = json;
        this.payloads = payloads;
        this.encryption = encryption;
        this.clock = clock;
    }

    @Transactional
    public ToolCallRecord start(AuthContext actor, String resourceId, DataQueryRequest input) {
        var resource = policy.manualUse(actor, resourceId);
        String id = UUID.randomUUID().toString();
        var arguments = json.tree(input);
        var raw = json.tree(
            Map.of("arguments", arguments, "configHash", resource.configHash(), "draftRevision", resource.revision()));
        var paths = new ArrayList<String>();
        for (int index = 0; index < input.filters().size(); index++) {
            paths.add("/filters/" + index + "/value");
        }
        var safe = payloads.redact(arguments, paths, List.of());
        calls.manualDataRead(id, actor, resource, payloads.argumentHash(raw),
            encryption.encrypt(raw, "tool-request:" + actor.enterpriseId() + ":" + id), safe, clock.instant());
        return calls.find(actor.enterpriseId(), id, false).orElseThrow();
    }

    @Transactional
    public void finish(ToolCallRecord call, JsonNode result, String code, String message) {
        var safe = payloads.redact(result, List.of(), List.of());
        calls.finish(call, code == null ? "succeeded" : "failed",
            encryption.encrypt(safe, "tool-result:" + call.enterpriseId() + ":" + call.id()), safe, code, message,
            clock.instant());
    }
}
