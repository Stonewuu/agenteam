package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkDependencyMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkDependencyRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 工作依赖在整项任务内检查，不能产生人事等待财务、财务又等待人事的循环。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CollaborationDependencyService {
    private final CollaborationWorkDependencyMapper dependencies;
    private final CollaborationWorkItemMapper works;
    private final Clock clock;

    public CollaborationDependencyService(CollaborationWorkDependencyMapper dependencies, CollaborationWorkItemMapper works, Clock clock) {
        this.dependencies = dependencies;
        this.works = works;
        this.clock = clock;
    }

    public List<CollaborationWorkItemRow> works(CollaborationTaskRow task) {
        return works.selectList(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).orderByAsc(CollaborationWorkItemRow::getCreatedAt, CollaborationWorkItemRow::getId));
    }

    public List<CollaborationWorkDependencyRow> edges(CollaborationTaskRow task) {
        return dependencies.selectList(new LambdaQueryWrapper<CollaborationWorkDependencyRow>().eq(CollaborationWorkDependencyRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkDependencyRow::getTaskId, task.getId()));
    }

    public void add(CollaborationTaskRow task, String work, List<String> required, String phase) {
        if (required.size() > 20 || !Set.of("start", "resume").contains(phase)) {
            throw ApiException.invalidField("dependsOnWorkIds", "最多选择二十项依赖工作。");
        }
        var rows = works(task);
        var ids = rows.stream().map(CollaborationWorkItemRow::getId).collect(Collectors.toSet());
        if (!ids.contains(work) || !ids.containsAll(required)) {
            throw ApiException.invalidField("dependsOnWorkIds", "依赖必须是当前任务内已存在的工作。");
        }
        var edges = edges(task);
        var graph = graph(edges);
        graph.computeIfAbsent(work, ignored -> new HashSet<>()).addAll(required);
        requireAcyclic(graph);
        for (String target : new HashSet<>(required)) {
            boolean exists = edges.stream().anyMatch(edge -> edge.getWorkId().equals(work) && edge.getDependsOnWorkId().equals(target) && edge.getPhase().equals(phase));
            if (!exists) {
                var edge = ChatRecords.initialize(new CollaborationWorkDependencyRow(), task.getEnterpriseId(), clock.instant());
                edge.setTaskId(task.getId());
                edge.setWorkId(work);
                edge.setDependsOnWorkId(target);
                edge.setPhase(phase);
                dependencies.insert(edge);
            }
        }
    }

    public String readiness(CollaborationTaskRow task, String work) {
        var indexed = works(task).stream().collect(Collectors.toMap(CollaborationWorkItemRow::getId, value -> value));
        boolean waiting = false;
        for (var edge : edges(task)) {
            if (!edge.getWorkId().equals(work)) {
                continue;
            }
            var target = indexed.get(edge.getDependsOnWorkId());
            if (target == null || Set.of("failed", "cancelled", "superseded").contains(target.getStatus())) {
                return "blocked";
            }
            waiting |= !Set.of("completed", "handed_off").contains(target.getStatus()) || target.getActiveRunId() != null;
        }
        return waiting ? "waiting_dependency" : "ready";
    }

    public void replace(CollaborationTaskRow task, CollaborationWorkItemRow previous, CollaborationWorkItemRow replacement) {
        if ("TOOL_RESULT_UNKNOWN".equals(previous.getErrorCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_RESULT_UNKNOWN", "原工作的业务操作结果尚未确认，不能通过替换工作再次执行。");
        }
        if (previous.getId().equals(task.getOwnerWorkId()) || previous.getActiveRunId() != null || previous.getReplacementWorkId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "WORK_BUSY", "原工作仍在执行、已经替换或仍负责最终交付。");
        }
        var edges = edges(task);
        var downstream = edges.stream().filter(edge -> edge.getDependsOnWorkId().equals(previous.getId()))
            .map(CollaborationWorkDependencyRow::getWorkId).collect(Collectors.toSet());
        if (works(task).stream().anyMatch(work -> downstream.contains(work.getId()) && work.getActiveRunId() != null && !work.getId().equals(task.getOwnerWorkId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "WORK_BUSY", "依赖原工作的岗位仍在执行，请等待它结束后替换。");
        }
        for (var edge : edges) {
            if (edge.getDependsOnWorkId().equals(previous.getId())) {
                edge.setDependsOnWorkId(replacement.getId());
            }
        }
        requireAcyclic(graph(edges));
        dependencies.update(new LambdaUpdateWrapper<CollaborationWorkDependencyRow>().eq(CollaborationWorkDependencyRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkDependencyRow::getTaskId, task.getId()).eq(CollaborationWorkDependencyRow::getDependsOnWorkId, previous.getId())
            .set(CollaborationWorkDependencyRow::getDependsOnWorkId, replacement.getId()).set(CollaborationWorkDependencyRow::getUpdatedAt, clock.instant()));
        if (works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getId, previous.getId()).eq(CollaborationWorkItemRow::getRevision, previous.getRevision())
            .set(CollaborationWorkItemRow::getReplacementWorkId, replacement.getId()).set(CollaborationWorkItemRow::getStatus, "superseded")
            .setIncrBy(CollaborationWorkItemRow::getRevision, 1)) != 1) {
            throw ApiException.versionConflict(previous.getRevision());
        }
    }

    private Map<String, Set<String>> graph(List<CollaborationWorkDependencyRow> edges) {
        var graph = new HashMap<String, Set<String>>();
        for (var edge : edges) {
            graph.computeIfAbsent(edge.getWorkId(), ignored -> new HashSet<>()).add(edge.getDependsOnWorkId());
        }
        return graph;
    }

    static void requireAcyclic(Map<String, Set<String>> graph) {
        var incoming = new HashMap<String, Integer>();
        graph.forEach((work, required) -> {
            incoming.putIfAbsent(work, 0);
            required.forEach(target -> incoming.merge(target, 1, Integer::sum));
        });
        var ready = new ArrayDeque<String>();
        incoming.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String next = ready.removeFirst();
            visited++;
            for (String target : graph.getOrDefault(next, Set.of())) {
                if (incoming.compute(target, (ignored, value) -> value - 1) == 0) {
                    ready.add(target);
                }
            }
        }
        if (visited != incoming.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "WORK_DEPENDENCY_CYCLE", "这些工作存在相互等待，请调整依赖关系。");
        }
    }
}
