package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.mapper.execution.RunJobMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.tool.SourceToolResultAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 文件内容沿用当前任务和原始资料的权限，持久保存不能绕过之后的撤权。
 */
@Service
public class WorkspaceAccessService {
    private final ExecutionAccessService executions;
    private final RunMapper runs;
    private final RunJobMapper jobs;
    private final ToolCallMapper calls;
    private final SourceToolResultAccessService sources;
    private final FileAccessService files;
    private final Clock clock;

    public WorkspaceAccessService(ExecutionAccessService executions, RunMapper runs, RunJobMapper jobs,
                                  ToolCallMapper calls,
                                  SourceToolResultAccessService sources, FileAccessService files, Clock clock) {
        this.executions = executions;
        this.runs = runs;
        this.jobs = jobs;
        this.calls = calls;
        this.sources = sources;
        this.files = files;
        this.clock = clock;
    }

    public AuthContext actor(RunRecord run, JobLease lease) {
        requireLease(run, lease);
        return executions.actor(run);
    }

    public void requireLease(RunRecord run, JobLease lease) {
        var current = runs.find(run.enterpriseId(), run.id(), false).orElseThrow(ExecutionStoppedException::new);
        if (!current.userId().equals(run.userId()) || !current.conversationId()
            .equals(run.conversationId()) || !current.status().equals("running")
            || current.cancelRequestedAt() != null || current.leaseVersion() != lease.version() || !jobs.valid(lease,
            clock.instant(), false)) {
            throw new ExecutionStoppedException();
        }
    }

    public void requireSources(AuthContext actor, Set<String> ids, Set<String> inputIds) {
        var ordered = new ArrayList<>(ids);
        for (int index = 0; index < ordered.size(); index += 100) {
            var batch = ordered.subList(index, Math.min(ordered.size(), index + 100));
            var found = calls.findMany(actor.enterpriseId(), batch);
            if (found.size() != batch.size()) {
                throw ResourceAuthorizationService.unavailable();
            }
            found.forEach(call -> sources.requireResult(actor, call));
        }
        for (String id : inputIds) {
            files.ready(actor, id);
        }
    }

    public record Origins(Set<String> results, Set<String> inputs) {
    }

    public void requireStoredWorkspace(RunRecord run, String session, WorkspaceSession scope) {
        if (scope.manifest().version() == null && !scope.cleared() && calls.hasWorkspaceFiles(run, session)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKSPACE_STORAGE_UNAVAILABLE",
                "当前节点无法读取此对话已保存的工作文件，请检查工作空间存储。");
        }
    }

    public Origins currentOrigins(RunRecord run, WorkspaceStore.Manifest saved) {
        var results = new LinkedHashSet<>(saved.sourceResultIds());
        var inputs = new LinkedHashSet<>(saved.inputFileIds());
        for (var call : calls.sourceResultsForContext(run)) {
            if (!saved.sessionId().equals(run.conversationId()) && !saved.sessionId()
                .equals(call.frameworkSessionId())) {
                continue;
            }
            if (Set.of("agent", "workflow").contains(call.resourceKind())) {
                if (call.resultRedacted() != null) {
                    call.resultRedacted().path("sourceResultIds").forEach(id -> results.add(id.asText()));
                    call.resultRedacted().path("inputFileIds").forEach(id -> inputs.add(id.asText()));
                }
            } else {
                results.add(call.id());
            }
        }
        return new Origins(results, inputs);
    }
}
