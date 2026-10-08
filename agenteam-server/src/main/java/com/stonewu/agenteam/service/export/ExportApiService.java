package com.stonewu.agenteam.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.export.ExportDefinitionMapper;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.model.export.request.ToolLogExportInput;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.tool.request.ToolLogQuery;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.background.PublicJobService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 重复请求只保存任务编号；每次返回前重新读取当前权限下的实际结果。
 */
@Service
public class ExportApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final ExportAccessService access;
    private final ExportJobTransactions transactions;
    private final PublicJobService jobs;
    private final ObjectMapper json;
    private final ExportDefinitionMapper definitions;

    public ExportApiService(AuthContextService identity, IdempotentRequestService requests, ExportAccessService access,
                            ExportJobTransactions transactions, PublicJobService jobs, ObjectMapper json,
                            ExportDefinitionMapper definitions) {
        this.identity = identity;
        this.requests = requests;
        this.access = access;
        this.transactions = transactions;
        this.jobs = jobs;
        this.json = json;
        this.definitions = definitions;
    }

    public ApiOperationResult conversation(String enterprise, String conversation, HttpServletRequest request) {
        return execute(enterprise, definitions.create("conversation", "conversationId", conversation), request);
    }

    public ApiOperationResult tools(String enterprise, ToolLogQuery input, HttpServletRequest request) {
        InputValidation.request(request, ToolLogExportInput.class);
        return execute(enterprise, definitions.create("tool_calls", "toolCalls", input), request);
    }

    public ApiOperationResult execute(String enterprise, ExportDefinition definition, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        var result = requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> access.authorize(identity.requireEnterprise(request.getSession(false), enterprise), definition, true),
            () -> ApiOperationResult.of(202, Map.of("jobId", transactions.create(actor, definition))));
        String id = json.valueToTree(result.data()).path("jobId").asText();
        return new ApiOperationResult(result.status(),
            jobs.get(identity.requireEnterprise(request.getSession(false), enterprise), id), result.replayed());
    }
}
