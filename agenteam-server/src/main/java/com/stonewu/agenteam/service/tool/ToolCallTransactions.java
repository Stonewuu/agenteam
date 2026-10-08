package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.ExecutionStepIds;
import com.stonewu.agenteam.mapper.execution.RunApprovalMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolApprovalSummaryMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.entity.ToolApprovalPolicy;
import com.stonewu.agenteam.model.execution.response.RunApprovalView;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.HttpCredentialService;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 调用前后的短事务固定参数和真实状态，数据库事务内不连接外部服务。
 */
@Service
public class ToolCallTransactions {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallTransactions.class);
    private final ToolCallMapper calls;
    private final RunApprovalMapper approvals;
    private final ExecutionToolCatalog catalog;
    private final ExecutionAccessService access;
    private final RunLifecycleService lifecycle;
    private final PayloadEncryption encryption;
    private final ToolPayloadMapper payloads;
    private final ToolApprovalSummaryMapper summaries;
    private final ResourceJson json;
    private final Clock clock;
    private final HttpCredentialService credentials;
    private final ToolSchemaValidation schemas;
    private final FileMapper files;
    private final ToolQueryPolicy queries;

    public ToolCallTransactions(ToolCallMapper calls, RunApprovalMapper approvals, ExecutionToolCatalog catalog,
                                ExecutionAccessService access,
                                RunLifecycleService lifecycle, PayloadEncryption encryption, ToolPayloadMapper payloads,
                                ToolApprovalSummaryMapper summaries, ResourceJson json, Clock clock,
                                HttpCredentialService credentials, ToolSchemaValidation schemas, FileMapper files,
                                ToolQueryPolicy queries) {
        this.calls = calls;
        this.approvals = approvals;
        this.catalog = catalog;
        this.access = access;
        this.lifecycle = lifecycle;
        this.encryption = encryption;
        this.payloads = payloads;
        this.summaries = summaries;
        this.json = json;
        this.clock = clock;
        this.credentials = credentials;
        this.schemas = schemas;
        this.files = files;
        this.queries = queries;
    }

    @Transactional
    public ToolCallRecord prepare(JobLease lease, ExecutionToolBinding binding, String session, String parentCall,
                                  ToolUseBlock use) {
        var run = current(lease, binding);
        if (use.getId() == null || use.getId().isBlank() || use.getId()
            .length() > 128 || session == null || session.isBlank() || session.length() > 255) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOL_ARGUMENTS_INVALID", "工具调用没有有效的关联编号。");
        }
        JsonNode input = json.tree(use.getInput());
        schemas.requireBoundedArguments(input);
        ApiException argumentFailure = null;
        try {
            schemas.arguments(binding.definition().inputSchema(), input);
        } catch (ApiException failure) {
            if (!failure.code().equals("TOOL_ARGUMENTS_INVALID")) {
                throw failure;
            }
            LOG.warn("工具参数未通过校验，将错误返回助手修正，执行编号 {}，工具调用编号 {}", run.id(), use.getId(),
                failure);
            argumentFailure = failure;
        }
        var previous = calls.framework(run, session, use.getId());
        if (previous.isPresent()) {
            verify(previous.get(), binding, input);
            return previous.get();
        }
        String id = UUID.randomUUID().toString();
        String credential = binding.config().path("credentialId").asText(null);
        var secrets = binding.resourceKind().equals("plugin") ? credentials.resolve(run.enterpriseId(), credential)
            .secretValues() : List.<String>of();
        var redacted = payloads.redact(input, binding.definition().redactPaths(), secrets);
        String arguments = payloads.argumentHash(input);
        var summary = summaries.map(binding, redacted);
        String requestHash = json.hash(
            json.tree(Map.of("id", id, "enterprise", run.enterpriseId(), "run", run.id(), "tool", binding.alias(),
                "schema", binding.definition().schemaHash(), "arguments", arguments, "request", redacted, "summary",
                summary)));
        var raw = json.tree(Map.of("arguments", input, "toolUse", JsonUtils.getJsonCodec().toJson(use)));
        String source = parentCall == null ? session : session + ":" + parentCall;
        String step = ExecutionStepIds.id(run, "tool-step:" + source + ":" + use.getId());
        if (binding.config().path("delegatedResult").asBoolean() && binding.definition().name()
            .equals("read_workflow_result")) {
            // 工作流已有真实执行步骤，保存返回值时沿用它，不创建额外工具步骤。
            String invocation = ExecutionStepIds.id(run, "workflow-call:" + session + ":" + use.getId());
            step = ExecutionStepIds.id(run, "workflow:" + invocation);
        }
        calls.prepare(id, run, step, binding, session, use.getId(), arguments, requestHash,
            encryption.encrypt(raw, requestBinding(run.enterpriseId(), id)), redacted, clock.instant());
        var prepared = calls.find(run.enterpriseId(), id, false).orElseThrow();
        if (argumentFailure != null) {
            var result = json.tree(Map.of("isError", true, "code", argumentFailure.code(),
                "content", "工具参数不符合已发布的要求，本次操作没有执行。请按工具定义修正参数后继续。"));
            calls.finish(prepared, "failed", encryption.encrypt(result, resultBinding(prepared)), result,
                argumentFailure.code(), argumentFailure.getReason(), clock.instant());
        } else if (!binding.readOnly() && calls.previouslyRejected(run, binding, arguments)) {
            reject(prepared);
        }
        return calls.find(run.enterpriseId(), id, false).orElseThrow();
    }

    public boolean rejected(RunRecord run, ExecutionToolBinding binding, JsonNode input) {
        return !binding.readOnly() && calls.previouslyRejected(run, binding, payloads.argumentHash(input));
    }

    @Transactional
    public void retryRead(JobLease lease, ExecutionToolBinding binding, String id) {
        var run = current(lease, binding);
        var call = calls.find(run.enterpriseId(), id, true).orElseThrow();
        if (!run.id().equals(call.runId()) || !binding.readOnly() || !call.readOnly()) {
            throw new IllegalStateException("只有已验证的只读请求可以重新读取");
        }
        verify(call, binding, arguments(call));
        if (call.status().equals("prepared") && call.resultEncrypted() == null) {
            return;
        }
        if (!List.of("REMOTE_TIMEOUT", "REMOTE_CONNECTION_FAILED", "REMOTE_SERVER_UNAVAILABLE")
            .contains(call.errorCode())) {
            throw new IllegalStateException("当前失败不是可自动重试的临时连接故障");
        }
        calls.retryRead(call, lease.version(), clock.instant());
    }

    @Transactional
    public ToolCallRecord start(JobLease lease, ExecutionToolBinding binding, String session, ToolUseBlock use) {
        var run = current(lease, binding);
        var call = calls.framework(run, session, use.getId())
            .orElseThrow(() -> new IllegalStateException("工具参数尚未固定保存"));
        verify(call, binding, json.tree(use.getInput()));
        if (call.status().equals("unknown")) {
            throw unknown();
        }
        if (List.of("succeeded", "failed", "cancelled").contains(call.status()) && call.resultEncrypted() != null) {
            return call;
        }
        requireApproval(run, call);
        if (!call.readOnly() && call.status().equals("running") && call.submittedAt() != null && queries.canQuery(
            binding)) {
            calls.resumeQuery(call, lease.version(), clock.instant());
        } else {
            calls.start(call, lease.version(), clock.instant());
        }
        return calls.find(call.enterpriseId(), call.id(), false).orElseThrow();
    }

    @Transactional
    public void submitted(JobLease lease, ExecutionToolBinding binding, String callId) {
        var run = current(lease, binding);
        lifecycle.remaining(run);
        var call = calls.find(lease.enterpriseId(), callId, true).orElseThrow();
        requireApproval(run, call);
        if (!call.readOnly() && (call.attemptCount() >= 2 || (call.attemptCount() > 0 && !queries.canRepeat(
            binding)))) {
            throw unknown();
        }
        calls.submitted(call, lease.version(), clock.instant());
    }

    @Transactional
    public void beforeQuery(JobLease lease, ExecutionToolBinding binding, String callId) {
        var run = current(lease, binding);
        lifecycle.remaining(run);
        var call = calls.find(lease.enterpriseId(), callId, true).orElseThrow();
        if (!queries.canQuery(binding) || call.submittedAt() == null) {
            throw unknown();
        }
        calls.queried(call, lease.version(), clock.instant());
    }

    @Transactional
    public void complete(JobLease lease, String id, ToolResultService.Prepared result, String code, String error) {
        var run = lifecycle.locked(lease.enterpriseId(), lease.runId());
        lifecycle.requireLease(run, lease);
        var call = calls.find(run.enterpriseId(), id, true).orElseThrow();
        if (result.file() != null) {
            files.generated(result.file(), clock.instant());
            if (call.toolName().equals("export_file") && result.redacted().path("workspaceExport").asBoolean()) {
                files.retain(files.find(run.enterpriseId(), result.file().id(), false).orElseThrow(), clock.instant());
            }
        }
        calls.finish(call, code == null ? "succeeded" : "failed",
            encryption.encrypt(result.modelResult(), resultBinding(call)), result.redacted(), code, error,
            clock.instant());
    }

    @Transactional
    public boolean failed(JobLease lease, String id, String code, String message) {
        var run = lifecycle.locked(lease.enterpriseId(), lease.runId());
        lifecycle.requireLease(run, lease);
        var call = calls.find(run.enterpriseId(), id, true).orElseThrow();
        if (call.resultEncrypted() != null && List.of("succeeded", "failed", "cancelled").contains(call.status())) {
            return false;
        }
        boolean unknown = !call.readOnly() && call.submittedAt() != null;
        JsonNode result = json.tree(Map.of("isError", true, "content",
            List.of(Map.of("type", "text", "text", unknown ? unknown().getReason() : message))));
        calls.finish(call, unknown ? "unknown" : "failed", encryption.encrypt(result, resultBinding(call)), result,
            unknown ? "TOOL_RESULT_UNKNOWN" : code, unknown ? unknown().getReason() : message, clock.instant());
        return unknown;
    }

    public JsonNode arguments(ToolCallRecord call) {
        return encryption.decrypt(call.requestEncrypted(), requestBinding(call.enterpriseId(), call.id()),
            JsonNode.class).path("arguments");
    }

    public ToolUseBlock originalCall(ToolCallRecord call) {
        var raw = encryption.decrypt(call.requestEncrypted(), requestBinding(call.enterpriseId(), call.id()),
            JsonNode.class);
        return JsonUtils.getJsonCodec().fromJson(raw.path("toolUse").asText(), ToolUseBlock.class);
    }

    public JsonNode result(ToolCallRecord call) {
        return encryption.decrypt(call.resultEncrypted(), resultBinding(call), JsonNode.class);
    }

    public RunApprovalView.Summary summary(ExecutionToolBinding binding, ToolCallRecord call) {
        return summaries.map(binding, call.requestRedacted());
    }

    public void reject(ToolCallRecord call) {
        var result = json.tree(Map.of("isError", true, "content", "用户已拒绝本次操作，未发送请求。"));
        calls.finish(call, "cancelled", encryption.encrypt(result, resultBinding(call)), result, "TOOL_USER_REJECTED",
            "用户已拒绝本次操作，未发送请求。", clock.instant());
    }

    private RunRecord current(JobLease lease, ExecutionToolBinding binding) {
        var run = lifecycle.locked(lease.enterpriseId(), lease.runId());
        lifecycle.requireLease(run, lease);
        if (!run.status().equals("running") || run.cancelRequestedAt() != null) {
            throw new RunLifecycleService.ExecutionStoppedException();
        }
        access.actor(run);
        catalog.requireCurrent(run, binding);
        return run;
    }

    private void requireApproval(RunRecord run, ToolCallRecord call) {
        if (call.readOnly()) {
            return;
        }
        var found = approvals.forTool(call);
        if (found.isEmpty() && !ToolApprovalPolicy.forRun(run).requiresApproval(call.operationClass())) {
            return;
        }
        var approval = found.orElseThrow(ToolCallTransactions::unapproved);
        if (!approval.status().equals("approved") || !approval.approverUserId().equals(run.userId())
            || !approval.expiresAt().isAfter(clock.instant()) || !approval.requestHash().equals(call.requestHash())) {
            throw unapproved();
        }
    }

    private void verify(ToolCallRecord call, ExecutionToolBinding binding, JsonNode input) {
        if (!Objects.equals(call.pluginToolId(), binding.pluginToolId()) || !Objects.equals(call.resourceVersionId(),
            binding.resourceVersionId())
            || !call.resourceId().equals(binding.resourceId()) || !call.toolName()
            .equals(binding.definition().name()) || !call.argumentHash().equals(payloads.argumentHash(input))) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_ARGUMENTS_CHANGED", "工具参数已经变化，不能沿用此前确认。");
        }
    }

    private String requestBinding(String enterprise, String id) {
        return "tool-request:" + enterprise + ":" + id;
    }

    private String resultBinding(ToolCallRecord call) {
        return "tool-result:" + call.enterpriseId() + ":" + call.id();
    }

    private static ApiException unapproved() {
        return new ApiException(HttpStatus.CONFLICT, "TOOL_APPROVAL_REQUIRED",
            "本次外部操作尚未获得有效确认，未发送请求。");
    }

    public static ApiException unknown() {
        return new ApiException(HttpStatus.CONFLICT, "TOOL_RESULT_UNKNOWN", "无法确认操作结果，请先到目标系统核对。");
    }
}
