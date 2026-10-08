package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 本地业务修改与工具结果一起提交，重复调用读取同一结果；事务中不访问外部服务。
 */
@Service
public class PlatformBuiltinInvocation {
    private final EnterpriseMapper enterprises;
    private final RunLifecycleService lifecycle;
    private final ExecutionAccessService access;
    private final ExecutionToolCatalog catalog;
    private final ToolCallMapper calls;
    private final ToolCallTransactions transactions;
    private final ToolResultService results;
    private final ToolSchemaValidation schemas;

    public PlatformBuiltinInvocation(EnterpriseMapper enterprises, RunLifecycleService lifecycle,
                                     ExecutionAccessService access,
                                     ExecutionToolCatalog catalog, ToolCallMapper calls,
                                     ToolCallTransactions transactions, ToolResultService results,
                                     ToolSchemaValidation schemas) {
        this.enterprises = enterprises;
        this.lifecycle = lifecycle;
        this.access = access;
        this.catalog = catalog;
        this.calls = calls;
        this.transactions = transactions;
        this.results = results;
        this.schemas = schemas;
    }

    public JsonNode read(PlatformBuiltinPluginAdapter adapter, RunRecord run, ToolCallRecord call, JsonNode arguments,
                         ToolCallControl control) {
        var actor = access.actor(run);
        control.beforeSend();
        var result = adapter.call(new BuiltinExecutionContext(actor, run, call.operationId()), call.toolName(),
            arguments);
        control.requireActive();
        access.actor(run);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JsonNode write(PlatformBuiltinPluginAdapter adapter, JobLease lease, ExecutionToolBinding binding,
                          ToolCallRecord expected,
                          JsonNode arguments, Duration timeout, ToolCallControl control) {
        long deadline = System.nanoTime() + timeout.toNanos();
        // 先锁企业再锁执行，与管理操作的锁定顺序一致。
        enterprises.lockEnterprise(lease.enterpriseId()).orElseThrow();
        var run = lifecycle.locked(lease.enterpriseId(), lease.runId());
        lifecycle.requireLease(run, lease);
        if (!run.status().equals("running") || run.cancelRequestedAt() != null) {
            throw new ExecutionStoppedException();
        }
        if (!run.mode().equals("interactive")) {
            throw new ApiException(HttpStatus.CONFLICT, "PLATFORM_WRITE_MODE_UNSUPPORTED",
                "请在普通对话中确认并修改待办或计划，预览和计划执行不能修改这些内容。");
        }
        var actor = access.actor(run);
        catalog.requireCurrent(run, binding);
        var call = calls.find(run.enterpriseId(), expected.id(), true).orElseThrow();
        if (!run.id().equals(call.runId()) || !run.userId().equals(call.actorUserId()) || !binding.resourceVersionId()
            .equals(call.resourceVersionId())
            || !binding.definition().name().equals(call.toolName()) || !transactions.arguments(call)
            .equals(arguments)) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_ARGUMENTS_CHANGED", "本次业务操作已经变化，请重新发起。");
        }
        if (call.resultEncrypted() != null) {
            return transactions.result(call);
        }
        control.beforeSend();
        var response = adapter.call(new BuiltinExecutionContext(actor, run, call.operationId()), call.toolName(),
            arguments);
        control.requireActive();
        if (System.nanoTime() > deadline) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "TOOL_EXECUTION_TIMEOUT",
                "本次业务操作超时，修改未提交。");
        }
        schemas.result(binding.definition().outputSchema(), response);
        if (response.toString().getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_RESULT_TOO_LARGE",
                "本次业务操作返回内容过多，修改未提交。");
        }
        var result = results.prepare(run, binding, response, List.of());
        transactions.complete(lease, call.id(), result, null, null);
        return result.modelResult();
    }
}
