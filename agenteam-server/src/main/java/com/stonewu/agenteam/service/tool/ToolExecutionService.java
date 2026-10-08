package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.mapper.tool.ToolProviderMapper;
import com.stonewu.agenteam.model.execution.entity.ExecutionLimits;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.security.entity.HttpConnectionCredentials;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.HttpCredentialService;
import com.stonewu.agenteam.service.tool.provider.ToolProviderContext;
import com.stonewu.agenteam.service.tool.provider.ToolProviderRegistry;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import com.stonewu.agenteam.service.workspace.WorkspaceToolService;
import io.agentscope.core.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 公共执行入口负责确认和调用记录，具体工具按固定来源交给扩展接口执行。
 */
@Service
public class ToolExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutionService.class);
    private final ToolCallTransactions transactions;
    private final ToolCallMapper calls;
    private final HttpCredentialService credentials;
    private final ToolResultService results;
    private final ToolPayloadMapper payloads;
    private final SourceToolExecutionService sources;
    private final ToolProviderRegistry providers;
    private final ToolProviderMapper providerMapper;
    private final ResourceJson json;
    private final WorkspaceToolService workspace;
    private final DelegatedToolResultService delegated;

    public ToolExecutionService(ToolCallTransactions transactions, ToolCallMapper calls,
                                HttpCredentialService credentials,
                                ToolResultService results, ToolPayloadMapper payloads,
                                SourceToolExecutionService sources,
                                ToolProviderRegistry providers, ToolProviderMapper providerMapper, ResourceJson json,
                                WorkspaceToolService workspace, DelegatedToolResultService delegated) {
        this.transactions = transactions;
        this.calls = calls;
        this.credentials = credentials;
        this.results = results;
        this.payloads = payloads;
        this.sources = sources;
        this.providers = providers;
        this.providerMapper = providerMapper;
        this.json = json;
        this.workspace = workspace;
        this.delegated = delegated;
    }

    public ToolResultBlock delegatedResult(RunRecord run, JobLease lease, String session, ToolUseBlock use,
                                           ToolResultBlock result, int maximum) {
        return delegated.save(run, lease, session, use, result, maximum);
    }

    public JsonNode invoke(RunRecord run, JobLease lease, ExecutionToolBinding binding, String session,
                           ToolUseBlock use, Duration remaining,
                           AtomicReference<ToolCallControl> cancellation, BooleanSupplier cancelled) {
        return invoke(run, lease, binding, session, use, remaining, cancellation, cancelled,
            results.settings().inlineBytes(0));
    }

    public ToolResultSettings resultSettings() {
        return results.settings();
    }

    public record ModelOutput(JsonNode result, List<ContentBlock> content) {
    }

    public ModelOutput invokeImage(RunRecord run, JobLease lease, ExecutionToolBinding binding, String session,
                                   ToolUseBlock use, Duration remaining,
                                   AtomicReference<ToolCallControl> cancellation, BooleanSupplier cancelled,
                                   int maximumBytes, Set<String> sharedResults) {
        var image = new AtomicReference<ImageBlock>();
        var result = invoke(run, lease, binding, session, use, remaining, cancellation, cancelled, maximumBytes,
            sharedResults, image::set);
        List<ContentBlock> content = new ArrayList<>();
        content.add(TextBlock.builder().text(result.toString()).build());
        if (image.get() != null && !result.path("isError").asBoolean(false)) {
            content.add(image.get());
        }
        return new ModelOutput(result, List.copyOf(content));
    }

    public JsonNode invoke(RunRecord run, JobLease lease, ExecutionToolBinding binding, String session,
                           ToolUseBlock use, Duration remaining,
                           AtomicReference<ToolCallControl> cancellation, BooleanSupplier cancelled, int maximumBytes) {
        return invoke(run, lease, binding, session, use, remaining, cancellation, cancelled, maximumBytes, Set.of());
    }

    public JsonNode invoke(RunRecord run, JobLease lease, ExecutionToolBinding binding, String session,
                           ToolUseBlock use, Duration remaining,
                           AtomicReference<ToolCallControl> cancellation, BooleanSupplier cancelled, int maximumBytes,
                           Set<String> sharedResults) {
        return invoke(run, lease, binding, session, use, remaining, cancellation, cancelled, maximumBytes,
            sharedResults, ignored -> {
            });
    }

    private JsonNode invoke(RunRecord run, JobLease lease, ExecutionToolBinding binding, String session,
                            ToolUseBlock use, Duration remaining,
                            AtomicReference<ToolCallControl> cancellation, BooleanSupplier cancelled, int maximumBytes,
                            Set<String> sharedResults,
                            Consumer<ImageBlock> imageOutput) {
        var call = transactions.start(lease, binding, session, use);
        if (call.resultEncrypted() != null) {
            var result = results.forModel(call, transactions.result(call), maximumBytes);
            if (WorkspaceToolDefinitions.handles(binding) && binding.definition().name()
                .equals("view_image") && result.path("workspaceImage").asBoolean(false)) {
                try {
                    imageOutput.accept(workspace.image(run, lease, session, result, remaining));
                } catch (ApiException failure) {
                    LOG.warn("重新读取已保存的图片调用失败，执行编号 {}，调用编号 {}", run.id(), call.id(), failure);
                    return json.tree(Map.of("isError", true, "content", failure.getReason()));
                }
            }
            return result;
        }
        try (var control = new ToolCallControl(() -> transactions.submitted(lease, binding, call.id()))) {
            cancellation.set(control);
            if (cancelled.getAsBoolean()) {
                control.close();
            }
            control.requireActive();
            String credential = binding.config().path("credentialId").asText(null);
            var auth = binding.resourceKind().equals("data") && binding.config().path("sourceType").asText()
                .equals("mysql")
                ? new HttpConnectionCredentials(Map.of(), sources.databaseSecrets(run, binding)) : credentials.resolve(
                run.enterpriseId(), credential);
            int limit = Math.min(binding.definition().timeoutSeconds(),
                binding.config().path("timeoutSeconds").asInt(30));
            Duration timeout = ExecutionLimits.minTimeout(remaining, Duration.ofSeconds(limit));
            JsonNode arguments = transactions.arguments(call);
            JsonNode response;
            WorkspaceToolService.Outcome workspaceResult = null;
            if (WorkspaceToolDefinitions.handles(binding)) {
                workspaceResult = workspace.invoke(run, lease, binding, call, session, arguments, timeout, control,
                    maximumBytes, sharedResults);
                response = workspaceResult.value();
            } else if (!binding.resourceKind().equals("plugin")) {
                response = sources.invoke(run, binding, arguments, timeout, control);
            } else {
                var source = providerMapper.source(binding);
                var context = new ToolProviderContext(run.enterpriseId(), timeout, remaining, control, run, lease,
                    binding, call);
                response = json.tree(providers.require(source.type())
                    .invoke(source, providerMapper.spec(binding.definition(), source), json.object(arguments),
                        context));
                if (context.persisted()) {
                    return results.forModel(calls.find(run.enterpriseId(), call.id(), false).orElseThrow(), response,
                        maximumBytes);
                }
            }
            control.requireActive();
            var secrets = new ArrayList<>(auth.secretValues());
            secrets.addAll(payloads.secretValues(arguments, binding.definition().redactPaths()));
            var prepared = results.prepare(run, binding, response, secrets, call.id(), maximumBytes);
            if (workspaceResult != null) {
                var stored = (ObjectNode) prepared.redacted().deepCopy();
                if (!workspaceResult.sourceResultIds().isEmpty()) {
                    stored.set("sourceResultIds", json.tree(workspaceResult.sourceResultIds()));
                }
                if (!workspaceResult.inputFileIds().isEmpty()) {
                    stored.set("inputFileIds", json.tree(workspaceResult.inputFileIds()));
                }
                if (workspaceResult.workspaceVersion() != null) {
                    stored.put("workspaceVersion", workspaceResult.workspaceVersion());
                }
                prepared = new ToolResultService.Prepared(prepared.modelResult(), stored,
                    workspaceResult.file() == null ? prepared.file() : workspaceResult.file());
            }
            prepared = results.referenceForHistory(call.id(), prepared, maximumBytes);
            boolean failed = response.path("isError").asBoolean(false);
            transactions.complete(lease, call.id(), prepared, failed ? "TOOL_EXECUTION_FAILED" : null,
                failed ? "工具返回执行失败，请查看结果。" : null);
            if (!failed && workspaceResult != null && workspaceResult.image() != null) {
                imageOutput.accept(workspaceResult.image());
            }
            return prepared.modelResult();
        } catch (RuntimeException failure) {
            LOG.error("工具调用失败，执行编号 {}，调用编号 {}", run.id(), call.id(), failure);
            String code = failure instanceof ApiException known ? known.code() : "TOOL_EXECUTION_FAILED";
            String message = failure instanceof ApiException known ? known.getReason() : "工具调用未能完成，请稍后重试。";
            if (transactions.failed(lease, call.id(), code, message)) {
                throw ToolCallTransactions.unknown();
            }
            var failed = calls.find(run.enterpriseId(), call.id(), false).orElseThrow();
            return transactions.result(failed);
        } finally {
            cancellation.set(null);
        }
    }
}
